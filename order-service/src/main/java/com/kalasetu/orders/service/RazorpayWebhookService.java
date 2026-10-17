package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.payment.RazorpaySignatureVerifier;
import com.kalasetu.orders.store.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Handles Razorpay webhooks. Razorpay retries until it receives a 2xx response, so anything that is
 * not worth retrying (unknown events, unknown orders) is acknowledged and logged rather than rejected.
 * See https://razorpay.com/docs/webhooks/
 */
@Service
public class RazorpayWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookService.class);
    private static final String PAYMENT_CAPTURED = "payment.captured";
    private static final String ORDER_PAID = "order.paid";
    private static final String PAYMENT_FAILED = "payment.failed";

    private final RazorpaySignatureVerifier signatureVerifier;
    private final OrderStore orderStore;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookService(RazorpaySignatureVerifier signatureVerifier, OrderStore orderStore,
                                  PaymentService paymentService, ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.orderStore = orderStore;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param eventId Razorpay's {@code X-Razorpay-Event-Id}; when absent the body hash is used, so an
     *                identical redelivery is still recognised
     */
    public void handle(byte[] rawBody, String signature, String eventId) {
        if (!signatureVerifier.isValidWebhookSignature(rawBody, signature)) {
            log.warn("Rejected Razorpay webhook with an invalid signature");
            throw new OrderException(ErrorCode.INVALID_WEBHOOK_SIGNATURE, "Webhook signature is invalid.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new OrderException(ErrorCode.VALIDATION_FAILED, "Webhook body is not valid JSON.");
        }
        String eventType = root.path("event").asString("");
        String resolvedEventId = eventId == null || eventId.isBlank()
                ? "body-" + Hashing.sha256Hex(new String(rawBody, StandardCharsets.UTF_8))
                : eventId;

        switch (eventType) {
            case PAYMENT_CAPTURED, ORDER_PAID -> handlePaymentCaptured(
                    root.path("payload").path("payment").path("entity"),
                    new PaymentService.WebhookEvent(resolvedEventId, eventType));
            case PAYMENT_FAILED -> log.info("Payment attempt failed for gateway order {}: {}",
                    root.path("payload").path("payment").path("entity").path("order_id").asString(""),
                    root.path("payload").path("payment").path("entity").path("error_description").asString(""));
            default -> log.debug("Ignoring Razorpay event {}", eventType);
        }
    }

    private void handlePaymentCaptured(JsonNode payment, PaymentService.WebhookEvent event) {
        String paymentId = payment.path("id").asString("");
        String gatewayOrderId = payment.path("order_id").asString("");
        long amountPaise = payment.path("amount").asLong(-1);
        if (paymentId.isEmpty() || gatewayOrderId.isEmpty()) {
            log.warn("Razorpay {} event {} has no payment or order id", event.type(), event.id());
            return;
        }
        Optional<Order> order = orderStore.findOrderByGatewayOrderId(gatewayOrderId);
        if (order.isEmpty()) {
            log.warn("Razorpay {} for unknown gateway order {}", event.type(), gatewayOrderId);
            return;
        }
        if (amountPaise != order.get().totalPaise()) {
            log.error("Payment {} amount {} does not match order {} total {}; not recording it",
                    paymentId, amountPaise, order.get().id(), order.get().totalPaise());
            return;
        }
        paymentService.recordCapturedPayment(order.get().id(), paymentId, "webhook", event);
    }
}
