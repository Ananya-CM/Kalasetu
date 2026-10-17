package com.kalasetu.orders.testsupport;

import com.kalasetu.orders.config.AppProperties;
import com.kalasetu.orders.domain.ShippingDetails;
import com.kalasetu.orders.service.CreateOrderCommand;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class TestFixtures {

    public static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    public static final String KEY_SECRET = "test_key_secret";
    public static final String WEBHOOK_SECRET = "test_webhook_secret";
    public static final String JOB_TOKEN = "test_job_token";
    public static final Duration RESERVATION_TTL = Duration.ofMinutes(15);
    public static final ShippingDetails SHIPPING =
            new ShippingDetails("Asha Rao", "asha@example.com", "12 MG Road, Bengaluru");

    private TestFixtures() {
    }

    public static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Razorpay(FakePaymentGateway.KEY_ID, KEY_SECRET, WEBHOOK_SECRET,
                        "https://api.razorpay.com", Duration.ofSeconds(5), Duration.ofSeconds(10)),
                new AppProperties.Orders(RESERVATION_TTL, 50, 100, 50, 200),
                new AppProperties.Jobs(JOB_TOKEN),
                new AppProperties.Cors(List.of("https://kalasetu-25a17.web.app")));
    }

    public static CreateOrderCommand command(CreateOrderCommand.Line... lines) {
        return new CreateOrderCommand(List.of(lines), SHIPPING);
    }

    public static CreateOrderCommand.Line line(String productId, int quantity) {
        return new CreateOrderCommand.Line(productId, quantity);
    }
}
