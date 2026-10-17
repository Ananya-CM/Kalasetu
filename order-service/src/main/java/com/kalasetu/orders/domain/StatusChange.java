package com.kalasetu.orders.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of an order's status history.
 *
 * @param actor who caused the change, for example {@code buyer:<uid>}, {@code seller:<uid>},
 *              {@code webhook} or {@code system}
 */
public record StatusChange(OrderStatus status, Instant at, String actor) {

    public StatusChange {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(actor, "actor");
    }
}
