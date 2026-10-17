package com.kalasetu.orders.payment;

import com.kalasetu.orders.config.AppProperties;
import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpaySignatureVerifierTest {

    private final RazorpaySignatureVerifier verifier = new RazorpaySignatureVerifier(TestFixtures.properties());

    @Test
    void matchesPublishedHmacSha256TestVector() {
        AppProperties properties = new AppProperties(
                new AppProperties.Razorpay("id", "unused", "key", "https://api.razorpay.com",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new AppProperties.Orders(Duration.ofMinutes(15), 50, 100, 50, 200),
                new AppProperties.Jobs(""),
                new AppProperties.Cors(List.of()));
        RazorpaySignatureVerifier vectorVerifier = new RazorpaySignatureVerifier(properties);
        byte[] body = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

        assertThat(vectorVerifier.isValidWebhookSignature(body,
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")).isTrue();
    }

    @Test
    void acceptsValidPaymentSignature() {
        String signature = RazorpayTestSignatures.paymentSignature("order_123", "pay_456");

        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", signature)).isTrue();
    }

    @Test
    void acceptsUppercaseHex() {
        String signature = RazorpayTestSignatures.paymentSignature("order_123", "pay_456").toUpperCase();

        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", signature)).isTrue();
    }

    @Test
    void rejectsSignatureForADifferentOrderOrPayment() {
        String signature = RazorpayTestSignatures.paymentSignature("order_123", "pay_456");

        assertThat(verifier.isValidPaymentSignature("order_999", "pay_456", signature)).isFalse();
        assertThat(verifier.isValidPaymentSignature("order_123", "pay_999", signature)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedSignatures() {
        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", null)).isFalse();
        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", "")).isFalse();
        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", "not-hex")).isFalse();
        assertThat(verifier.isValidPaymentSignature("order_123", "pay_456", "abc")).isFalse();
    }

    @Test
    void webhookSignatureCoversTheExactBytes() {
        byte[] body = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        String signature = RazorpayTestSignatures.webhookSignature(body);

        assertThat(verifier.isValidWebhookSignature(body, signature)).isTrue();
        assertThat(verifier.isValidWebhookSignature("{\"event\": \"payment.captured\"}".getBytes(StandardCharsets.UTF_8),
                signature)).isFalse();
    }

    @Test
    void paymentAndWebhookUseDifferentSecrets() {
        byte[] payload = "order_123|pay_456".getBytes(StandardCharsets.UTF_8);
        String paymentSignature = RazorpayTestSignatures.paymentSignature("order_123", "pay_456");

        assertThat(verifier.isValidWebhookSignature(payload, paymentSignature)).isFalse();
    }
}
