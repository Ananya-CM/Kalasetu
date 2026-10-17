package com.kalasetu.orders.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/** All service settings, bound from {@code kalasetu.*} properties (environment variables in production). */
@Validated
@ConfigurationProperties(prefix = "kalasetu")
public record AppProperties(
        @Valid @NotNull Razorpay razorpay,
        @Valid @NotNull Orders orders,
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull Cors cors) {

    public record Razorpay(
            @NotBlank String keyId,
            @NotBlank String keySecret,
            @NotBlank String webhookSecret,
            @DefaultValue("https://api.razorpay.com") String baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {
    }

    public record Orders(
            @DefaultValue("15m") Duration reservationTtl,
            @DefaultValue("50") @Min(1) @Max(100) int maxItemsPerOrder,
            @DefaultValue("100") @Min(1) @Max(1000) int maxQuantityPerItem,
            @DefaultValue("50") @Min(1) @Max(200) int maxListSize,
            @DefaultValue("200") @Min(1) @Max(1000) int expiryBatchSize) {
    }

    /** {@code token} protects the internal job endpoint; leave empty to disable the endpoint. */
    public record Jobs(@DefaultValue("") String token) {
    }

    public record Cors(@DefaultValue({}) List<String> allowedOrigins) {
    }
}
