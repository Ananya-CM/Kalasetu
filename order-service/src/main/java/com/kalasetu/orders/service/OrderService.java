package com.kalasetu.orders.service;

import com.kalasetu.orders.config.AppProperties;
import com.kalasetu.orders.domain.IdempotencyRecord;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.payment.GatewayOrder;
import com.kalasetu.orders.payment.PaymentGateway;
import com.kalasetu.orders.store.OrderStore;
import com.kalasetu.orders.store.OrderTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Creating orders, reading them, and seller fulfilment updates. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore orderStore;
    private final PaymentGateway paymentGateway;
    private final Clock clock;
    private final AppProperties.Orders settings;

    public OrderService(OrderStore orderStore, PaymentGateway paymentGateway, Clock clock, AppProperties properties) {
        this.orderStore = orderStore;
        this.paymentGateway = paymentGateway;
        this.clock = clock;
        this.settings = properties.orders();
    }

    /**
     * Creates an order and reserves stock, or returns the existing order if this idempotency key was
     * already used for the same request. Prices always come from the database, never the client.
     */
    public OrderPlacement createOrder(String buyerId, String idempotencyKey, CreateOrderCommand command) {
        IdempotencyKeys.requireValid(idempotencyKey);
        List<CreateOrderCommand.Line> lines = normalize(command.lines());
        String fingerprint = IdempotencyKeys.fingerprint(lines, command.shipping());
        String keyHash = IdempotencyKeys.storageKey(buyerId, idempotencyKey);
        String newOrderId = orderStore.newOrderId();

        OrderPlacement placement = orderStore.inTransaction(tx -> {
            Optional<IdempotencyRecord> existing = tx.findIdempotencyRecord(keyHash);
            if (existing.isPresent()) {
                return replay(tx, existing.get(), fingerprint);
            }
            Instant now = clock.instant();
            List<OrderItem> items = new ArrayList<>(lines.size());
            Map<String, Long> remainingStock = new LinkedHashMap<>();
            for (CreateOrderCommand.Line line : lines) {
                Product product = tx.findProduct(line.productId())
                        .orElseThrow(() -> new OrderException(ErrorCode.PRODUCT_NOT_FOUND,
                                "Product " + line.productId() + " is not available."));
                if (!product.hasStockFor(line.quantity())) {
                    throw new OrderException(ErrorCode.OUT_OF_STOCK,
                            "Only " + product.stock() + " left of " + product.name() + ".");
                }
                if (product.tracksStock()) {
                    remainingStock.put(product.id(), product.stock() - line.quantity());
                }
                items.add(new OrderItem(product.id(), product.sellerId(), product.name(), product.pricePaise(),
                        line.quantity()));
            }
            Order order = Order.placed(newOrderId, buyerId, items, command.shipping(), now,
                    now.plus(settings.reservationTtl()));
            remainingStock.forEach(tx::updateProductStock);
            tx.saveOrder(order);
            tx.saveIdempotencyRecord(keyHash, new IdempotencyRecord(order.id(), fingerprint, now));
            return new OrderPlacement(order, false);
        });

        if (!placement.replayed()) {
            log.info("Order {} created for buyer {} with {} item(s), total {} paise",
                    placement.order().id(), buyerId, placement.order().items().size(), placement.order().totalPaise());
        }
        return new OrderPlacement(ensureGatewayOrder(placement.order()), placement.replayed());
    }

    public Order getOrder(String uid, String orderId) {
        return findVisibleOrder(uid, orderId);
    }

    public List<Order> listOrders(String uid, ViewerRole role, int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, settings.maxListSize());
        return switch (role) {
            case BUYER -> orderStore.findOrdersByBuyer(uid, limit);
            case SELLER -> orderStore.findOrdersBySeller(uid, limit);
        };
    }

    /** Moves an order forward in fulfilment. Repeating the current status is accepted and changes nothing. */
    public Order updateStatusAsSeller(String sellerId, String orderId, OrderStatus target) {
        if (!target.isSellerSettable()) {
            throw new OrderException(ErrorCode.STATUS_NOT_SETTABLE,
                    "Sellers can only set SHIPPED or DELIVERED.");
        }
        requireValidOrderId(orderId);
        Order updated = orderStore.inTransaction(tx -> {
            Order order = tx.findOrder(orderId)
                    .filter(found -> found.isVisibleTo(sellerId))
                    .orElseThrow(OrderService::orderNotFound);
            if (!order.isSeller(sellerId)) {
                throw new OrderException(ErrorCode.NOT_ORDER_SELLER, "Only a seller on this order can update it.");
            }
            if (order.status() == target) {
                return order;
            }
            Order next = order.transitionTo(target, clock.instant(), "seller:" + sellerId);
            tx.saveOrder(next);
            return next;
        });
        log.info("Order {} is now {} (seller {})", orderId, updated.status(), sellerId);
        return updated;
    }

    public String paymentGatewayKeyId() {
        return paymentGateway.publicKeyId();
    }

    Order findVisibleOrder(String uid, String orderId) {
        requireValidOrderId(orderId);
        return orderStore.findOrder(orderId)
                .filter(order -> order.isVisibleTo(uid))
                .orElseThrow(OrderService::orderNotFound);
    }

    /**
     * Creates the payment-provider order if it doesn't exist yet. Runs outside the creation
     * transaction because transactions can be retried and must not call external services.
     */
    private Order ensureGatewayOrder(Order order) {
        if (order.status() != OrderStatus.PENDING_PAYMENT || order.gatewayOrderId() != null) {
            return order;
        }
        GatewayOrder gatewayOrder = paymentGateway.createOrder(order.id(), order.totalPaise(), order.currency());
        return orderStore.inTransaction(tx -> {
            Order current = tx.findOrder(order.id()).orElseThrow(OrderService::orderNotFound);
            if (current.gatewayOrderId() != null) {
                // A concurrent retry linked a gateway order first; the one created here simply goes unused.
                return current;
            }
            Order linked = current.withGatewayOrderId(gatewayOrder.id(), clock.instant());
            tx.saveOrder(linked);
            return linked;
        });
    }

    private static OrderPlacement replay(OrderTransaction tx, IdempotencyRecord existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new OrderException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different request.");
        }
        Order order = tx.findOrder(existing.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record points to missing order " + existing.orderId()));
        return new OrderPlacement(order, true);
    }

    /** Validates line limits and merges repeated products into one line. */
    private List<CreateOrderCommand.Line> normalize(List<CreateOrderCommand.Line> lines) {
        if (lines.isEmpty()) {
            throw new OrderException(ErrorCode.VALIDATION_FAILED, "An order needs at least one item.");
        }
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (CreateOrderCommand.Line line : lines) {
            if (!DocumentIds.isValid(line.productId())) {
                throw new OrderException(ErrorCode.PRODUCT_NOT_FOUND,
                        "Product " + line.productId() + " is not available.");
            }
            if (line.quantity() < 1) {
                throw new OrderException(ErrorCode.VALIDATION_FAILED, "Quantity must be at least 1.");
            }
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        if (quantities.size() > settings.maxItemsPerOrder()) {
            throw new OrderException(ErrorCode.VALIDATION_FAILED,
                    "An order can contain at most " + settings.maxItemsPerOrder() + " different products.");
        }
        List<CreateOrderCommand.Line> merged = new ArrayList<>(quantities.size());
        quantities.forEach((productId, quantity) -> {
            if (quantity > settings.maxQuantityPerItem()) {
                throw new OrderException(ErrorCode.VALIDATION_FAILED,
                        "At most " + settings.maxQuantityPerItem() + " units of one product per order.");
            }
            merged.add(new CreateOrderCommand.Line(productId, quantity));
        });
        return merged;
    }

    private static void requireValidOrderId(String orderId) {
        if (!DocumentIds.isValid(orderId)) {
            throw orderNotFound();
        }
    }

    private static OrderException orderNotFound() {
        return new OrderException(ErrorCode.ORDER_NOT_FOUND, "Order not found.");
    }
}
