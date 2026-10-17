package com.kalasetu.orders.payment;

/** The payment provider. Razorpay in production; replaced by a fake in tests. */
public interface PaymentGateway {

    /**
     * Creates a payment order the buyer can pay against.
     *
     * @param receipt our own order id, stored by the provider for reconciliation
     * @throws PaymentGatewayException if the provider cannot be reached or rejects the request
     */
    GatewayOrder createOrder(String receipt, long amountPaise, String currency);

    /** The public key the browser checkout widget needs. */
    String publicKeyId();
}
