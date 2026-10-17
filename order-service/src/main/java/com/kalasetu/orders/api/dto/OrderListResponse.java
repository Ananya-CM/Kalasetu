package com.kalasetu.orders.api.dto;

import java.util.List;

public record OrderListResponse(List<OrderResponse> orders) {
}
