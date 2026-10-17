package com.kalasetu.orders.api.dto;

import com.kalasetu.orders.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull OrderStatus status) {
}
