package com.kalasetu.orders.store;

import com.kalasetu.orders.domain.Order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Persistence for orders and the records that keep them consistent. */
public interface OrderStore {

    /**
     * Runs {@code work} in a transaction. The work may be executed more than once if the transaction
     * is retried after contention, so it must have no side effects outside the transaction.
     * Runtime exceptions thrown by the work abort the transaction and are rethrown unchanged.
     */
    <T> T inTransaction(Function<OrderTransaction, T> work);

    String newOrderId();

    Optional<Order> findOrder(String orderId);

    Optional<Order> findOrderByGatewayOrderId(String gatewayOrderId);

    List<Order> findOrdersByBuyer(String buyerId, int limit);

    List<Order> findOrdersBySeller(String sellerId, int limit);

    /** Orders still awaiting payment whose reservation ended before {@code cutoff}. */
    List<Order> findPendingOrdersExpiringBefore(Instant cutoff, int limit);
}
