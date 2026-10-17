package com.kalasetu.orders.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.StatusChange;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
        String orderId,
        OrderStatus status,
        List<Item> items,
        long totalPaise,
        String currency,
        Shipping shipping,
        String gatewayOrderId,
        String gatewayKeyId,
        List<StatusEntry> statusHistory,
        Instant createdAt,
        Instant expiresAt) {

    public record Item(String productId, String sellerId, String name, long unitPricePaise, int quantity,
                       long lineTotalPaise) {

        static Item from(OrderItem item) {
            return new Item(item.productId(), item.sellerId(), item.name(), item.unitPricePaise(), item.quantity(),
                    item.lineTotalPaise());
        }
    }

    public record Shipping(String name, String email, String address) {
    }

    public record StatusEntry(OrderStatus status, Instant at) {

        static StatusEntry from(StatusChange change) {
            return new StatusEntry(change.status(), change.at());
        }
    }

    /**
     * @param gatewayKeyId included only while the order awaits payment, since that is the only time
     *                     the browser needs it
     */
    public static OrderResponse from(Order order, String gatewayKeyId) {
        boolean awaitingPayment = order.status() == OrderStatus.PENDING_PAYMENT;
        return new OrderResponse(
                order.id(),
                order.status(),
                order.items().stream().map(Item::from).toList(),
                order.totalPaise(),
                order.currency(),
                new Shipping(order.shipping().name(), order.shipping().email(), order.shipping().address()),
                awaitingPayment ? order.gatewayOrderId() : null,
                awaitingPayment ? gatewayKeyId : null,
                order.statusHistory().stream().map(StatusEntry::from).toList(),
                order.createdAt(),
                awaitingPayment ? order.expiresAt() : null);
    }
}
