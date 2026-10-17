package com.kalasetu.orders.testsupport;

import com.kalasetu.orders.payment.GatewayOrder;
import com.kalasetu.orders.payment.PaymentGateway;
import com.kalasetu.orders.payment.PaymentGatewayException;

import java.util.ArrayList;
import java.util.List;

public class FakePaymentGateway implements PaymentGateway {

    public static final String KEY_ID = "rzp_test_key";

    private final List<String> receipts = new ArrayList<>();
    private int failuresRemaining;

    /** Makes the next {@code count} calls fail as if Razorpay were down. */
    public void failNextCalls(int count) {
        failuresRemaining = count;
    }

    public synchronized int createdCount() {
        return receipts.size();
    }

    @Override
    public synchronized GatewayOrder createOrder(String receipt, long amountPaise, String currency) {
        if (failuresRemaining > 0) {
            failuresRemaining--;
            throw new PaymentGatewayException("Simulated outage");
        }
        receipts.add(receipt);
        return new GatewayOrder("order_" + receipt, amountPaise, currency);
    }

    @Override
    public String publicKeyId() {
        return KEY_ID;
    }
}
