package com.kalasetu.orders.payment;

import com.kalasetu.orders.testsupport.TestFixtures;

import java.nio.charset.StandardCharsets;

/** Produces signatures the way Razorpay does, using the test secrets. */
public final class RazorpayTestSignatures {

    private RazorpayTestSignatures() {
    }

    public static String paymentSignature(String gatewayOrderId, String paymentId) {
        return RazorpaySignatureVerifier.hmacHex(TestFixtures.KEY_SECRET.getBytes(StandardCharsets.UTF_8),
                (gatewayOrderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
    }

    public static String webhookSignature(byte[] body) {
        return RazorpaySignatureVerifier.hmacHex(TestFixtures.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), body);
    }
}
