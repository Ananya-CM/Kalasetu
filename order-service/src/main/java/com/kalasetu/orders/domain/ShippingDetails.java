package com.kalasetu.orders.domain;

import java.util.Objects;

public record ShippingDetails(String name, String email, String address) {

    public ShippingDetails {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(address, "address");
    }
}
