package com.kalasetu.orders.payment;

public record GatewayOrder(String id, long amountPaise, String currency) {
}
