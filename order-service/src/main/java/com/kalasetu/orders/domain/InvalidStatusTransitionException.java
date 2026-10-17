package com.kalasetu.orders.domain;

public class InvalidStatusTransitionException extends RuntimeException {

    private final OrderStatus from;
    private final OrderStatus to;

    public InvalidStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Cannot move order from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus from() {
        return from;
    }

    public OrderStatus to() {
        return to;
    }
}
