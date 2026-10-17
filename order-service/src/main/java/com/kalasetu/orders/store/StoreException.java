package com.kalasetu.orders.store;

/** The database could not complete an operation (network failure, timeout, contention that never cleared). */
public class StoreException extends RuntimeException {

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
