package com.kalasetu.orders.store.firestore;

/** Firestore collection names used by the service. */
final class FirestoreCollections {

    static final String PRODUCTS = "products";
    static final String ORDERS = "orders";
    static final String IDEMPOTENCY_KEYS = "idempotencyKeys";
    static final String WEBHOOK_EVENTS = "webhookEvents";
    static final String LEDGER_ENTRIES = "ledgerEntries";

    private FirestoreCollections() {
    }
}
