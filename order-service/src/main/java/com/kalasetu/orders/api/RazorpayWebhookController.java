package com.kalasetu.orders.api;

import com.kalasetu.orders.service.RazorpayWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Receives Razorpay webhooks. Authenticated by signature, not by user token. */
@RestController
public class RazorpayWebhookController {

    private final RazorpayWebhookService webhookService;

    public RazorpayWebhookController(RazorpayWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /** The body is taken as raw bytes because the signature is computed over the exact bytes sent. */
    @PostMapping("/api/v1/webhooks/razorpay")
    public ResponseEntity<Void> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        webhookService.handle(rawBody, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
