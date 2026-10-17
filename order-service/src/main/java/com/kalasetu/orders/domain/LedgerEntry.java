package com.kalasetu.orders.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An append-only record of money owed. A seller's balance is the sum of their entries; corrections
 * are made with new entries, never by editing old ones.
 */
public record LedgerEntry(String orderId, String sellerId, long amountPaise, Type type, Instant createdAt) {

    public enum Type {
        SELLER_CREDIT
    }

    public LedgerEntry {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
