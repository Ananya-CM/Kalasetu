package com.kalasetu.orders.store.firestore;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.kalasetu.orders.domain.IdempotencyRecord;
import com.kalasetu.orders.domain.LedgerEntry;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.store.OrderTransaction;
import com.kalasetu.orders.store.StoreException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Firestore requires every read in a transaction to happen before the first write, so writes are
 * collected here and applied by {@link #applyWrites()} once the business logic has finished.
 */
final class FirestoreOrderTransaction implements OrderTransaction {

    private final Firestore firestore;
    private final Transaction transaction;
    private final List<Consumer<Transaction>> pendingWrites = new ArrayList<>();

    FirestoreOrderTransaction(Firestore firestore, Transaction transaction) {
        this.firestore = firestore;
        this.transaction = transaction;
    }

    @Override
    public Optional<Product> findProduct(String productId) {
        return FirestoreMapper.toProduct(read(FirestoreCollections.PRODUCTS, productId));
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        return FirestoreMapper.toOrder(read(FirestoreCollections.ORDERS, orderId));
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotencyRecord(String keyHash) {
        return FirestoreMapper.toIdempotencyRecord(read(FirestoreCollections.IDEMPOTENCY_KEYS, keyHash));
    }

    @Override
    public boolean isWebhookEventProcessed(String eventId) {
        return read(FirestoreCollections.WEBHOOK_EVENTS, eventId).exists();
    }

    @Override
    public void saveOrder(Order order) {
        Map<String, Object> data = FirestoreMapper.fromOrder(order);
        pendingWrites.add(tx -> tx.set(firestore.collection(FirestoreCollections.ORDERS).document(order.id()), data));
    }

    @Override
    public void updateProductStock(String productId, long newStock) {
        pendingWrites.add(tx -> tx.update(firestore.collection(FirestoreCollections.PRODUCTS).document(productId),
                "stock", newStock));
    }

    @Override
    public void saveIdempotencyRecord(String keyHash, IdempotencyRecord idempotencyRecord) {
        Map<String, Object> data = FirestoreMapper.fromIdempotencyRecord(idempotencyRecord);
        // create() fails if the document already exists, a second guard against duplicate keys.
        pendingWrites.add(tx -> tx.create(firestore.collection(FirestoreCollections.IDEMPOTENCY_KEYS).document(keyHash), data));
    }

    @Override
    public void markWebhookEventProcessed(String eventId, String eventType, Instant processedAt) {
        Map<String, Object> data = Map.of(
                "type", eventType,
                "processedAt", FirestoreMapper.toTimestamp(processedAt));
        pendingWrites.add(tx -> tx.set(firestore.collection(FirestoreCollections.WEBHOOK_EVENTS).document(eventId), data));
    }

    @Override
    public void addLedgerEntry(LedgerEntry entry) {
        Map<String, Object> data = FirestoreMapper.fromLedgerEntry(entry);
        pendingWrites.add(tx -> tx.create(firestore.collection(FirestoreCollections.LEDGER_ENTRIES).document(), data));
    }

    void applyWrites() {
        pendingWrites.forEach(write -> write.accept(transaction));
    }

    private DocumentSnapshot read(String collection, String documentId) {
        ApiFuture<DocumentSnapshot> future = transaction.get(firestore.collection(collection).document(documentId));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted while reading " + collection + "/" + documentId, e);
        } catch (ExecutionException e) {
            throw new StoreException("Failed to read " + collection + "/" + documentId, e.getCause());
        }
    }
}
