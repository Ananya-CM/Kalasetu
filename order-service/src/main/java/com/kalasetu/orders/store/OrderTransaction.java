package com.kalasetu.orders.store;

import com.kalasetu.orders.domain.IdempotencyRecord;
import com.kalasetu.orders.domain.LedgerEntry;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.Product;

import java.time.Instant;
import java.util.Optional;

/**
 * Reads and writes inside one atomic, serializable transaction.
 *
 * <p>Reads see the state committed before the transaction started; writes are buffered and applied
 * together when the transaction commits. Callers must therefore not expect to read back their own
 * writes within the same transaction. This matches Firestore, which requires all reads to happen
 * before any write.
 */
public interface OrderTransaction {

    Optional<Product> findProduct(String productId);

    Optional<Order> findOrder(String orderId);

    Optional<IdempotencyRecord> findIdempotencyRecord(String keyHash);

    boolean isWebhookEventProcessed(String eventId);

    void saveOrder(Order order);

    void updateProductStock(String productId, long newStock);

    void saveIdempotencyRecord(String keyHash, IdempotencyRecord idempotencyRecord);

    void markWebhookEventProcessed(String eventId, String eventType, Instant processedAt);

    void addLedgerEntry(LedgerEntry entry);
}
