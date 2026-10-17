package com.kalasetu.orders.payment;

import com.kalasetu.orders.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the HMAC-SHA256 signatures Razorpay attaches to checkout results and webhooks.
 * Comparisons are constant-time so the check doesn't leak how many characters matched.
 */
@Component
public class RazorpaySignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final byte[] keySecret;
    private final byte[] webhookSecret;

    public RazorpaySignatureVerifier(AppProperties properties) {
        this.keySecret = properties.razorpay().keySecret().getBytes(StandardCharsets.UTF_8);
        this.webhookSecret = properties.razorpay().webhookSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** Checkout result: signature over {@code gatewayOrderId|paymentId} with the API key secret. */
    public boolean isValidPaymentSignature(String gatewayOrderId, String paymentId, String signature) {
        byte[] payload = (gatewayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8);
        return matches(hmac(keySecret, payload), signature);
    }

    /** Webhook: signature over the raw request body with the webhook secret. */
    public boolean isValidWebhookSignature(byte[] rawBody, String signature) {
        return matches(hmac(webhookSecret, rawBody), signature);
    }

    static String hmacHex(byte[] secret, byte[] payload) {
        return HexFormat.of().formatHex(hmac(secret, payload));
    }

    private static boolean matches(byte[] expected, String providedHex) {
        if (providedHex == null || providedHex.isBlank()) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(providedHex.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private static byte[] hmac(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret, HMAC_SHA256));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
