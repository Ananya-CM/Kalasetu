package com.kalasetu.orders.service;

import com.kalasetu.orders.config.AppProperties;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.store.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Expires orders that were not paid in time and returns their reserved stock. Triggered by Cloud
 * Scheduler; each run handles one batch, and orders it doesn't reach are picked up by the next run.
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final OrderStore orderStore;
    private final Clock clock;
    private final int batchSize;

    public OrderExpiryService(OrderStore orderStore, Clock clock, AppProperties properties) {
        this.orderStore = orderStore;
        this.clock = clock;
        this.batchSize = properties.orders().expiryBatchSize();
    }

    /** @return how many orders were expired in this run */
    public int expireOverdueOrders() {
        Instant now = clock.instant();
        List<Order> candidates = orderStore.findPendingOrdersExpiringBefore(now, batchSize);
        int expired = 0;
        for (Order candidate : candidates) {
            try {
                if (expire(candidate.id(), now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // One bad order must not stop the rest of the batch.
                log.error("Failed to expire order {}", candidate.id(), e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid order(s)", expired);
        }
        return expired;
    }

    private boolean expire(String orderId, Instant now) {
        return orderStore.inTransaction(tx -> {
            Order order = tx.findOrder(orderId).orElse(null);
            // Re-checked inside the transaction: the order may have been paid since the query ran.
            if (order == null || !order.isExpiredAt(now)) {
                return false;
            }
            for (OrderItem item : order.items()) {
                tx.findProduct(item.productId())
                        .filter(Product::tracksStock)
                        .ifPresent(product -> tx.updateProductStock(product.id(), product.stock() + item.quantity()));
            }
            tx.saveOrder(order.transitionTo(OrderStatus.EXPIRED, now, "system"));
            return true;
        });
    }
}
