package com.kalasetu.orders.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an order. Every status change must go through {@link #canTransitionTo(OrderStatus)}
 * so that invalid transitions are rejected in one place.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    EXPIRED,
    REFUND_REQUIRED,
    SHIPPED,
    DELIVERED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OrderStatus.class);
    private static final Set<OrderStatus> SELLER_SETTABLE = EnumSet.of(SHIPPED, DELIVERED);

    static {
        ALLOWED_TRANSITIONS.put(PENDING_PAYMENT, EnumSet.of(PAID, EXPIRED));
        ALLOWED_TRANSITIONS.put(EXPIRED, EnumSet.of(PAID, REFUND_REQUIRED));
        ALLOWED_TRANSITIONS.put(PAID, EnumSet.of(SHIPPED));
        ALLOWED_TRANSITIONS.put(SHIPPED, EnumSet.of(DELIVERED));
        ALLOWED_TRANSITIONS.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_TRANSITIONS.put(REFUND_REQUIRED, EnumSet.noneOf(OrderStatus.class));
    }

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED_TRANSITIONS.get(this).contains(next);
    }

    /** Statuses a seller may set through the API. Everything else is driven by payments or expiry. */
    public boolean isSellerSettable() {
        return SELLER_SETTABLE.contains(this);
    }

    /** True once the buyer's money has been received for this order. */
    public boolean isPaid() {
        return this == PAID || this == SHIPPED || this == DELIVERED;
    }
}
