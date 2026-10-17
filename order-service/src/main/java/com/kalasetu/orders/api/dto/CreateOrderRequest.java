package com.kalasetu.orders.api.dto;

import com.kalasetu.orders.domain.ShippingDetails;
import com.kalasetu.orders.service.CreateOrderCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Item> items,
        @NotNull @Valid Shipping shipping) {

    public record Item(
            @NotBlank @Size(max = 128) String productId,
            @Min(1) @Max(1000) int quantity) {
    }

    public record Shipping(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 500) String address) {
    }

    public CreateOrderCommand toCommand() {
        return new CreateOrderCommand(
                items.stream().map(item -> new CreateOrderCommand.Line(item.productId(), item.quantity())).toList(),
                new ShippingDetails(shipping.name().trim(), shipping.email().trim(), shipping.address().trim()));
    }
}
