package com.kalasetu.orders.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of an order. State changes return a new instance; persistence is the
 * store's job.
 */
public record Order(
        String id,
        String buyerId,
        List<OrderItem> items,
        long totalPaise,
        String currency,
        ShippingDetails shipping,
        OrderStatus status,
        String gatewayOrderId,
        String gatewayPaymentId,
        List<StatusChange> statusHistory,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(shipping, "shipping");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one item");
        }
        items = List.copyOf(items);
        statusHistory = List.copyOf(statusHistory);
        long computedTotal = items.stream().mapToLong(OrderItem::lineTotalPaise).reduce(0L, Math::addExact);
        if (computedTotal != totalPaise) {
            throw new IllegalArgumentException("totalPaise does not match the sum of the items");
        }
    }

    /** Creates a new order awaiting payment. */
    public static Order placed(String id, String buyerId, List<OrderItem> items, ShippingDetails shipping,
                               Instant now, Instant expiresAt) {
        long total = items.stream().mapToLong(OrderItem::lineTotalPaise).reduce(0L, Math::addExact);
        StatusChange created = new StatusChange(OrderStatus.PENDING_PAYMENT, now, "buyer:" + buyerId);
        return new Order(id, buyerId, items, total, Money.CURRENCY_INR, shipping, OrderStatus.PENDING_PAYMENT,
                null, null, List.of(created), now, now, expiresAt);
    }

    public Order transitionTo(OrderStatus next, Instant at, String actor) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(status, next);
        }
        List<StatusChange> history = new ArrayList<>(statusHistory);
        history.add(new StatusChange(next, at, actor));
        return new Order(id, buyerId, items, totalPaise, currency, shipping, next, gatewayOrderId,
                gatewayPaymentId, history, createdAt, at, expiresAt);
    }

    public Order withGatewayOrderId(String newGatewayOrderId, Instant at) {
        return new Order(id, buyerId, items, totalPaise, currency, shipping, status, newGatewayOrderId,
                gatewayPaymentId, statusHistory, createdAt, at, expiresAt);
    }

    public Order withGatewayPaymentId(String newGatewayPaymentId, Instant at) {
        return new Order(id, buyerId, items, totalPaise, currency, shipping, status, gatewayOrderId,
                newGatewayPaymentId, statusHistory, createdAt, at, expiresAt);
    }

    /** Distinct sellers on this order, in item order. */
    public List<String> sellerIds() {
        return items.stream().map(OrderItem::sellerId).distinct().toList();
    }

    /** Amount owed to each seller for this order, keyed by seller id. */
    public Map<String, Long> totalsBySeller() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (OrderItem item : items) {
            totals.merge(item.sellerId(), item.lineTotalPaise(), Math::addExact);
        }
        return totals;
    }

    public boolean isBuyer(String uid) {
        return buyerId.equals(uid);
    }

    public boolean isSeller(String uid) {
        return items.stream().anyMatch(item -> item.sellerId().equals(uid));
    }

    public boolean isVisibleTo(String uid) {
        return isBuyer(uid) || isSeller(uid);
    }

    public boolean isExpiredAt(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && !now.isBefore(expiresAt);
    }
}
