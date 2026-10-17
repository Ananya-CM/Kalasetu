package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.ShippingDetails;

import java.util.List;
import java.util.Objects;

public record CreateOrderCommand(List<Line> lines, ShippingDetails shipping) {

    public record Line(String productId, int quantity) {

        public Line {
            Objects.requireNonNull(productId, "productId");
        }
    }

    public CreateOrderCommand {
        Objects.requireNonNull(shipping, "shipping");
        lines = List.copyOf(lines);
    }
}
