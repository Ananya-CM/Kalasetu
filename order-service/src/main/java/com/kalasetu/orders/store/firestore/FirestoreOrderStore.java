package com.kalasetu.orders.store.firestore;

import com.google.api.core.ApiFuture;
import com.google.cloud.BaseServiceException;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.store.OrderStore;
import com.kalasetu.orders.store.OrderTransaction;
import com.kalasetu.orders.store.StoreException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

@Repository
public class FirestoreOrderStore implements OrderStore {

    private final Firestore firestore;

    public FirestoreOrderStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public <T> T inTransaction(Function<OrderTransaction, T> work) {
        ApiFuture<T> future = firestore.runTransaction(transaction -> {
            // A fresh wrapper per attempt: Firestore may retry this function after contention.
            FirestoreOrderTransaction orderTransaction = new FirestoreOrderTransaction(firestore, transaction);
            T result = work.apply(orderTransaction);
            orderTransaction.applyWrites();
            return result;
        });
        return await(future, "transaction");
    }

    @Override
    public String newOrderId() {
        return firestore.collection(FirestoreCollections.ORDERS).document().getId();
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        return FirestoreMapper.toOrder(await(firestore.collection(FirestoreCollections.ORDERS).document(orderId).get(),
                "read order " + orderId));
    }

    @Override
    public Optional<Order> findOrderByGatewayOrderId(String gatewayOrderId) {
        Query query = firestore.collection(FirestoreCollections.ORDERS)
                .whereEqualTo("gatewayOrderId", gatewayOrderId)
                .limit(1);
        return runQuery(query).stream().findFirst();
    }

    @Override
    public List<Order> findOrdersByBuyer(String buyerId, int limit) {
        Query query = firestore.collection(FirestoreCollections.ORDERS)
                .whereEqualTo("buyerId", buyerId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit);
        return runQuery(query);
    }

    @Override
    public List<Order> findOrdersBySeller(String sellerId, int limit) {
        Query query = firestore.collection(FirestoreCollections.ORDERS)
                .whereArrayContains("sellerIds", sellerId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit);
        return runQuery(query);
    }

    @Override
    public List<Order> findPendingOrdersExpiringBefore(Instant cutoff, int limit) {
        Query query = firestore.collection(FirestoreCollections.ORDERS)
                .whereEqualTo("status", OrderStatus.PENDING_PAYMENT.name())
                .whereLessThan("expiresAt", FirestoreMapper.toTimestamp(cutoff))
                .orderBy("expiresAt")
                .limit(limit);
        return runQuery(query);
    }

    private List<Order> runQuery(Query query) {
        QuerySnapshot snapshot = await(query.get(), "query orders");
        return snapshot.getDocuments().stream()
                .map(FirestoreMapper::toOrder)
                .flatMap(Optional::stream)
                .toList();
    }

    private static <T> T await(ApiFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("Interrupted during " + operation, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // Business exceptions thrown inside a transaction surface here unchanged; database errors are wrapped.
            if (cause instanceof RuntimeException runtimeException && !(cause instanceof BaseServiceException)) {
                throw runtimeException;
            }
            throw new StoreException("Firestore " + operation + " failed", cause);
        }
    }
}
