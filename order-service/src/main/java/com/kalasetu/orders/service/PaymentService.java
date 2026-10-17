package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.LedgerEntry;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.payment.RazorpaySignatureVerifier;
import com.kalasetu.orders.store.OrderStore;
import com.kalasetu.orders.store.OrderTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Records captured payments. The browser callback and the provider webhook both end up in
 * {@link #recordCapturedPayment}, which is safe to call repeatedly and concurrently for the same payment.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderStore orderStore;
    private final RazorpaySignatureVerifier signatureVerifier;
    private final Clock clock;

    public PaymentService(OrderStore orderStore, RazorpaySignatureVerifier signatureVerifier, Clock clock) {
        this.orderStore = orderStore;
        this.signatureVerifier = signatureVerifier;
        this.clock = clock;
    }

    /** Confirms a payment reported by the buyer's browser after Razorpay Checkout succeeds. */
    public Order confirmCheckoutPayment(String buyerId, String orderId, String paymentId, String signature) {
        if (!DocumentIds.isValid(orderId)) {
            throw new OrderException(ErrorCode.ORDER_NOT_FOUND, "Order not found.");
        }
        Order order = orderStore.findOrder(orderId)
                .filter(found -> found.isBuyer(buyerId))
                .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
        if (order.gatewayOrderId() == null) {
            throw new OrderException(ErrorCode.PAYMENT_NOT_INITIATED, "Payment has not been started for this order.");
        }
        // The signature is computed over this order's own gateway order id, so a valid signature
        // for a different order cannot be replayed against this one.
        if (!signatureVerifier.isValidPaymentSignature(order.gatewayOrderId(), paymentId, signature)) {
            log.warn("Invalid payment signature for order {} from buyer {}", orderId, buyerId);
            throw new OrderException(ErrorCode.INVALID_PAYMENT_SIGNATURE, "Payment could not be verified.");
        }
        return recordCapturedPayment(orderId, paymentId, "buyer:" + buyerId, null);
    }

    /**
     * Applies a verified payment to an order.
     *
     * @param webhookEvent the provider event that reported it, or {@code null} for the browser callback;
     *                     an event already processed is ignored
     */
    Order recordCapturedPayment(String orderId, String paymentId, String actor, WebhookEvent webhookEvent) {
        PaymentOutcome outcome = orderStore.inTransaction(tx -> {
            Order order = tx.findOrder(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORDER_NOT_FOUND, "Order not found."));
            if (webhookEvent != null && tx.isWebhookEventProcessed(webhookEvent.id())) {
                return new PaymentOutcome(order, PaymentOutcome.Kind.ALREADY_RECORDED);
            }
            Instant now = clock.instant();
            PaymentOutcome result = switch (order.status()) {
                case PENDING_PAYMENT -> new PaymentOutcome(markPaid(tx, order, paymentId, actor, now),
                        PaymentOutcome.Kind.PAID);
                case EXPIRED -> recordLatePayment(tx, order, paymentId, actor, now);
                case PAID, SHIPPED, DELIVERED -> new PaymentOutcome(order, paymentId.equals(order.gatewayPaymentId())
                        ? PaymentOutcome.Kind.ALREADY_RECORDED
                        : PaymentOutcome.Kind.DUPLICATE_PAYMENT);
                case REFUND_REQUIRED -> new PaymentOutcome(order, PaymentOutcome.Kind.ALREADY_RECORDED);
            };
            if (webhookEvent != null) {
                tx.markWebhookEventProcessed(webhookEvent.id(), webhookEvent.type(), now);
            }
            return result;
        });

        switch (outcome.kind()) {
            case PAID -> log.info("Order {} paid with payment {} ({})", orderId, paymentId, actor);
            case REFUND_REQUIRED -> log.error("Order {} was paid after expiry and stock is gone; payment {} needs a refund",
                    orderId, paymentId);
            case DUPLICATE_PAYMENT -> log.error("Order {} received a second payment {} (first was {}); review for refund",
                    orderId, paymentId, outcome.order().gatewayPaymentId());
            case ALREADY_RECORDED -> log.debug("Payment {} for order {} was already recorded", paymentId, orderId);
        }
        return outcome.order();
    }

    /** The reservation lapsed before payment arrived: take stock again if it is still there, otherwise flag for refund. */
    private PaymentOutcome recordLatePayment(OrderTransaction tx, Order order, String paymentId, String actor,
                                             Instant now) {
        Map<String, Long> remainingStock = new LinkedHashMap<>();
        boolean allAvailable = true;
        for (OrderItem item : order.items()) {
            Optional<Product> product = tx.findProduct(item.productId());
            if (product.isEmpty() || !product.get().hasStockFor(item.quantity())) {
                allAvailable = false;
                break;
            }
            if (product.get().tracksStock()) {
                remainingStock.put(item.productId(), product.get().stock() - item.quantity());
            }
        }
        if (!allAvailable) {
            Order flagged = order.withGatewayPaymentId(paymentId, now)
                    .transitionTo(OrderStatus.REFUND_REQUIRED, now, actor);
            tx.saveOrder(flagged);
            return new PaymentOutcome(flagged, PaymentOutcome.Kind.REFUND_REQUIRED);
        }
        remainingStock.forEach(tx::updateProductStock);
        return new PaymentOutcome(markPaid(tx, order, paymentId, actor, now), PaymentOutcome.Kind.PAID);
    }

    private static Order markPaid(OrderTransaction tx, Order order, String paymentId, String actor, Instant now) {
        Order paid = order.withGatewayPaymentId(paymentId, now).transitionTo(OrderStatus.PAID, now, actor);
        tx.saveOrder(paid);
        order.totalsBySeller().forEach((sellerId, amountPaise) -> tx.addLedgerEntry(
                new LedgerEntry(order.id(), sellerId, amountPaise, LedgerEntry.Type.SELLER_CREDIT, now)));
        return paid;
    }

    record WebhookEvent(String id, String type) {
    }

    private record PaymentOutcome(Order order, Kind kind) {

        enum Kind {
            PAID,
            ALREADY_RECORDED,
            REFUND_REQUIRED,
            DUPLICATE_PAYMENT
        }
    }
}
