package com.kalasetu.orders.service;

/** A business rule rejected the request. The message is safe to show to the client. */
public class OrderException extends RuntimeException {

    private final ErrorCode code;

    public OrderException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
