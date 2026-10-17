package com.kalasetu.orders.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The values Razorpay Checkout hands to the browser after a successful payment. */
public record ConfirmPaymentRequest(
        @NotBlank @Size(max = 64) String gatewayPaymentId,
        @NotBlank @Size(max = 128) String signature) {
}
