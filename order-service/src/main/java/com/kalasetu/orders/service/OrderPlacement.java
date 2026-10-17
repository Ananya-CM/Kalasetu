package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.Order;

/**
 * Result of a create-order request.
 *
 * @param replayed true when the idempotency key matched an earlier request and the existing order was returned
 */
public record OrderPlacement(Order order, boolean replayed) {
}
