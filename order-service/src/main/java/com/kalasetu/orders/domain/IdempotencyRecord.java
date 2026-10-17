package com.kalasetu.orders.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Links a client-supplied idempotency key to the order it created.
 *
 * @param requestFingerprint hash of the request body, used to detect a key reused for a different request
 */
public record IdempotencyRecord(String orderId, String requestFingerprint, Instant createdAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
