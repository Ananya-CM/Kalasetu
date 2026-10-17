package com.kalasetu.orders.domain;

import java.util.Objects;

/** A line of an order. Name and price are copied at purchase time so later product edits don't change the order. */
public record OrderItem(String productId, String sellerId, String name, long unitPricePaise, int quantity) {

    public OrderItem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(name, "name");
        if (unitPricePaise <= 0) {
            throw new IllegalArgumentException("unitPricePaise must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public long lineTotalPaise() {
        return Math.multiplyExact(unitPricePaise, quantity);
    }
}
