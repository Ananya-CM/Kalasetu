package com.kalasetu.orders.testsupport;

import com.kalasetu.orders.domain.IdempotencyRecord;
import com.kalasetu.orders.domain.LedgerEntry;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.store.OrderStore;
import com.kalasetu.orders.store.OrderTransaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A serializable in-memory store. Transactions run one at a time; reads see committed state and
 * writes are buffered until the work completes, mirroring the Firestore implementation.
 */
public class InMemoryOrderStore implements OrderStore {

    private final Map<String, Product> products = new HashMap<>();
    private final Map<String, Order> orders = new HashMap<>();
    private final Map<String, IdempotencyRecord> idempotencyRecords = new HashMap<>();
    private final Map<String, String> processedWebhookEvents = new HashMap<>();
    private final List<LedgerEntry> ledgerEntries = new ArrayList<>();
    private final AtomicInteger transactionCount = new AtomicInteger();

    public synchronized void putProduct(Product product) {
        products.put(product.id(), product);
    }

    public synchronized Product product(String productId) {
        return products.get(productId);
    }

    public synchronized void putOrder(Order order) {
        orders.put(order.id(), order);
    }

    public synchronized List<LedgerEntry> ledgerEntries() {
        return List.copyOf(ledgerEntries);
    }

    public synchronized int orderCount() {
        return orders.size();
    }

    public int transactionCount() {
        return transactionCount.get();
    }

    @Override
    public synchronized <T> T inTransaction(Function<OrderTransaction, T> work) {
        transactionCount.incrementAndGet();
        BufferedTransaction transaction = new BufferedTransaction();
        T result = work.apply(transaction);
        transaction.pendingWrites.forEach(Runnable::run);
        return result;
    }

    @Override
    public String newOrderId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public synchronized Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public synchronized Optional<Order> findOrderByGatewayOrderId(String gatewayOrderId) {
        return orders.values().stream()
                .filter(order -> gatewayOrderId.equals(order.gatewayOrderId()))
                .findFirst();
    }

    @Override
    public synchronized List<Order> findOrdersByBuyer(String buyerId, int limit) {
        return orders.values().stream()
                .filter(order -> order.buyerId().equals(buyerId))
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Order> findOrdersBySeller(String sellerId, int limit) {
        return orders.values().stream()
                .filter(order -> order.sellerIds().contains(sellerId))
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized List<Order> findPendingOrdersExpiringBefore(Instant cutoff, int limit) {
        return orders.values().stream()
                .filter(order -> order.status() == OrderStatus.PENDING_PAYMENT && order.expiresAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Order::expiresAt))
                .limit(limit)
                .toList();
    }

    private final class BufferedTransaction implements OrderTransaction {

        private final List<Runnable> pendingWrites = new ArrayList<>();

        @Override
        public Optional<Product> findProduct(String productId) {
            return Optional.ofNullable(products.get(productId));
        }

        @Override
        public Optional<Order> findOrder(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public Optional<IdempotencyRecord> findIdempotencyRecord(String keyHash) {
            return Optional.ofNullable(idempotencyRecords.get(keyHash));
        }

        @Override
        public boolean isWebhookEventProcessed(String eventId) {
            return processedWebhookEvents.containsKey(eventId);
        }

        @Override
        public void saveOrder(Order order) {
            pendingWrites.add(() -> orders.put(order.id(), order));
        }

        @Override
        public void updateProductStock(String productId, long newStock) {
            pendingWrites.add(() -> {
                Product current = products.get(productId);
                products.put(productId, new Product(current.id(), current.sellerId(), current.name(),
                        current.pricePaise(), newStock));
            });
        }

        @Override
        public void saveIdempotencyRecord(String keyHash, IdempotencyRecord idempotencyRecord) {
            pendingWrites.add(() -> {
                if (idempotencyRecords.putIfAbsent(keyHash, idempotencyRecord) != null) {
                    throw new IllegalStateException("Idempotency record already exists");
                }
            });
        }

        @Override
        public void markWebhookEventProcessed(String eventId, String eventType, Instant processedAt) {
            pendingWrites.add(() -> processedWebhookEvents.put(eventId, eventType));
        }

        @Override
        public void addLedgerEntry(LedgerEntry entry) {
            pendingWrites.add(() -> ledgerEntries.add(entry));
        }
    }
}
