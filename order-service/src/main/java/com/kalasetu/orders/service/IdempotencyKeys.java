package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.ShippingDetails;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Validation, per-user scoping and request fingerprinting for idempotency keys. */
final class IdempotencyKeys {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private static final char SEPARATOR = '\u001F';

    private IdempotencyKeys() {
    }

    static void requireValid(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new OrderException(ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be 8-128 characters of letters, digits, '-' or '_'.");
        }
    }

    /** Keys are scoped per user, so one user can never collide with, or replay, another user's key. */
    static String storageKey(String uid, String key) {
        return Hashing.sha256Hex(uid + SEPARATOR + key);
    }

    /**
     * A hash of what the request asks for, independent of item order. Two requests with the same
     * key but different fingerprints are a client bug and are rejected.
     */
    static String fingerprint(List<CreateOrderCommand.Line> lines, ShippingDetails shipping) {
        StringBuilder canonical = new StringBuilder();
        lines.stream()
                .sorted(Comparator.comparing(CreateOrderCommand.Line::productId))
                .forEach(line -> canonical.append(line.productId()).append(SEPARATOR)
                        .append(line.quantity()).append(SEPARATOR));
        canonical.append(shipping.name()).append(SEPARATOR)
                .append(shipping.email()).append(SEPARATOR)
                .append(shipping.address());
        return Hashing.sha256Hex(canonical.toString());
    }
}
