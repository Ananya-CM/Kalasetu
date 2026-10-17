package com.kalasetu.orders.security;

public class InvalidIdTokenException extends RuntimeException {

    public InvalidIdTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
