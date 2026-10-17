package com.kalasetu.orders.domain;

import java.util.Objects;

/**
 * The fields of a marketplace product that matter for ordering.
 *
 * @param stock units available, or {@code null} when the seller does not track stock for this product
 */
public record Product(String id, String sellerId, String name, long pricePaise, Long stock) {

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(name, "name");
        if (pricePaise <= 0) {
            throw new IllegalArgumentException("pricePaise must be positive");
        }
        if (stock != null && stock < 0) {
            throw new IllegalArgumentException("stock must not be negative");
        }
    }

    public boolean tracksStock() {
        return stock != null;
    }

    public boolean hasStockFor(int quantity) {
        return !tracksStock() || stock >= quantity;
    }
}
