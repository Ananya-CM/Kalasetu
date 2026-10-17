package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.payment.RazorpaySignatureVerifier;
import com.kalasetu.orders.payment.RazorpayTestSignatures;
import com.kalasetu.orders.testsupport.FakePaymentGateway;
import com.kalasetu.orders.testsupport.InMemoryOrderStore;
import com.kalasetu.orders.testsupport.MutableClock;
import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static com.kalasetu.orders.testsupport.TestFixtures.command;
import static com.kalasetu.orders.testsupport.TestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RazorpayWebhookServiceTest {

    private InMemoryOrderStore store;
    private RazorpayWebhookService webhookService;
    private Order order;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        MutableClock clock = new MutableClock(TestFixtures.NOW);
        OrderService orderService = new OrderService(store, new FakePaymentGateway(), clock, TestFixtures.properties());
        RazorpaySignatureVerifier verifier = new RazorpaySignatureVerifier(TestFixtures.properties());
        PaymentService paymentService = new PaymentService(store, verifier, clock);
        webhookService = new RazorpayWebhookService(verifier, store, paymentService, JsonMapper.builder().build());
        store.putProduct(new Product("pot", "seller-a", "Terracotta pot", 45_000, 3L));
        order = orderService.createOrder("buyer-1", "attempt-00001", command(line("pot", 1))).order();
    }

    private static byte[] capturedEvent(String event, String paymentId, String gatewayOrderId, long amount) {
        return """
                {"entity":"event","event":"%s","payload":{"payment":{"entity":{
                "id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"captured"}}}}
                """.formatted(event, paymentId, gatewayOrderId, amount).getBytes(StandardCharsets.UTF_8);
    }

    private void deliver(byte[] body, String eventId) {
        webhookService.handle(body, RazorpayTestSignatures.webhookSignature(body), eventId);
    }

    private Order storedOrder() {
        return store.findOrder(order.id()).orElseThrow();
    }

    @Test
    void paymentCapturedMarksOrderPaid() {
        deliver(capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), order.totalPaise()), "evt_1");

        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PAID);
        assertThat(storedOrder().statusHistory().getLast().actor()).isEqualTo("webhook");
        assertThat(store.ledgerEntries()).hasSize(1);
    }

    @Test
    void redeliveredEventIsIgnored() {
        byte[] body = capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), order.totalPaise());
        deliver(body, "evt_1");
        int transactionsAfterFirst = store.transactionCount();

        deliver(body, "evt_1");

        assertThat(store.ledgerEntries()).hasSize(1);
        assertThat(store.transactionCount()).isEqualTo(transactionsAfterFirst + 1);
        assertThat(storedOrder().statusHistory()).hasSize(2);
    }

    @Test
    void orderPaidAfterPaymentCapturedChangesNothing() {
        deliver(capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), order.totalPaise()), "evt_1");

        deliver(capturedEvent("order.paid", "pay_1", order.gatewayOrderId(), order.totalPaise()), "evt_2");

        assertThat(storedOrder().statusHistory()).hasSize(2);
        assertThat(store.ledgerEntries()).hasSize(1);
    }

    @Test
    void eventWithoutIdHeaderIsDedupedByBody() {
        byte[] body = capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), order.totalPaise());

        deliver(body, null);
        deliver(body, "");

        assertThat(store.ledgerEntries()).hasSize(1);
    }

    @Test
    void amountMismatchIsNotRecorded() {
        deliver(capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), 100), "evt_1");

        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void unknownGatewayOrderIsAcknowledgedWithoutChanges() {
        deliver(capturedEvent("payment.captured", "pay_1", "order_unknown", 45_000), "evt_1");

        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void failedPaymentLeavesOrderOpenForRetry() {
        byte[] body = """
                {"event":"payment.failed","payload":{"payment":{"entity":{"id":"pay_x","order_id":"%s",
                "error_description":"Card declined"}}}}
                """.formatted(order.gatewayOrderId()).getBytes(StandardCharsets.UTF_8);

        deliver(body, "evt_1");

        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void invalidSignatureIsRejected() {
        byte[] body = capturedEvent("payment.captured", "pay_1", order.gatewayOrderId(), order.totalPaise());

        assertThatThrownBy(() -> webhookService.handle(body, "deadbeef", "evt_1"))
                .isInstanceOf(OrderException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void signedButMalformedBodyIsRejected() {
        byte[] body = "not json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> deliver(body, "evt_1"))
                .isInstanceOf(OrderException.class)
                .extracting("code").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void unrelatedEventsAreIgnored() {
        deliver("{\"event\":\"refund.created\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8), "evt_1");

        assertThat(storedOrder().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }
}
