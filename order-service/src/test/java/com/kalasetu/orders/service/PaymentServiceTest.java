package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.LedgerEntry;
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

import java.time.Duration;

import static com.kalasetu.orders.testsupport.TestFixtures.command;
import static com.kalasetu.orders.testsupport.TestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {

    private static final String BUYER = "buyer-1";

    private InMemoryOrderStore store;
    private MutableClock clock;
    private OrderService orderService;
    private OrderExpiryService expiryService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        clock = new MutableClock(TestFixtures.NOW);
        orderService = new OrderService(store, new FakePaymentGateway(), clock, TestFixtures.properties());
        expiryService = new OrderExpiryService(store, clock, TestFixtures.properties());
        paymentService = new PaymentService(store, new RazorpaySignatureVerifier(TestFixtures.properties()), clock);
        store.putProduct(new Product("pot", "seller-a", "Terracotta pot", 45_000, 2L));
        store.putProduct(new Product("scarf", "seller-b", "Silk scarf", 100_000, null));
    }

    private Order placeOrder(int pots) {
        return orderService.createOrder(BUYER, "attempt-" + pots + "-0001", command(line("pot", pots), line("scarf", 1)))
                .order();
    }

    private Order confirm(Order order, String paymentId) {
        return paymentService.confirmCheckoutPayment(BUYER, order.id(), paymentId,
                RazorpayTestSignatures.paymentSignature(order.gatewayOrderId(), paymentId));
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((OrderException) thrown).code();
    }

    @Test
    void validCheckoutPaymentMarksOrderPaidAndCreditsEachSeller() {
        Order order = placeOrder(1);

        Order paid = confirm(order, "pay_1");

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.gatewayPaymentId()).isEqualTo("pay_1");
        assertThat(store.ledgerEntries())
                .extracting(LedgerEntry::sellerId, LedgerEntry::amountPaise)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("seller-a", 45_000L),
                        org.assertj.core.groups.Tuple.tuple("seller-b", 100_000L));
    }

    @Test
    void confirmingTwiceIsHarmless() {
        Order order = placeOrder(1);
        confirm(order, "pay_1");

        Order again = confirm(order, "pay_1");

        assertThat(again.status()).isEqualTo(OrderStatus.PAID);
        assertThat(again.statusHistory()).hasSize(2);
        assertThat(store.ledgerEntries()).hasSize(2);
    }

    @Test
    void forgedSignatureIsRejected() {
        Order order = placeOrder(1);

        assertThatThrownBy(() -> paymentService.confirmCheckoutPayment(BUYER, order.id(), "pay_1", "00ff"))
                .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.INVALID_PAYMENT_SIGNATURE));
        assertThat(store.findOrder(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void signatureFromAnotherOrderCannotPayThisOne() {
        Order mine = placeOrder(1);
        Order other = orderService.createOrder("buyer-2", "other-attempt-01", command(line("scarf", 1))).order();
        String otherSignature = RazorpayTestSignatures.paymentSignature(other.gatewayOrderId(), "pay_other");

        assertThatThrownBy(() -> paymentService.confirmCheckoutPayment(BUYER, mine.id(), "pay_other", otherSignature))
                .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.INVALID_PAYMENT_SIGNATURE));
    }

    @Test
    void onlyTheBuyerCanConfirm() {
        Order order = placeOrder(1);

        assertThatThrownBy(() -> paymentService.confirmCheckoutPayment("seller-a", order.id(), "pay_1",
                RazorpayTestSignatures.paymentSignature(order.gatewayOrderId(), "pay_1")))
                .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void latePaymentAfterExpiryReReservesStockWhenAvailable() {
        Order order = placeOrder(1);
        clock.advance(Duration.ofMinutes(20));
        expiryService.expireOverdueOrders();
        assertThat(store.product("pot").stock()).isEqualTo(2L);

        Order paid = confirm(order, "pay_late");

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(store.product("pot").stock()).isEqualTo(1L);
        assertThat(store.ledgerEntries()).hasSize(2);
    }

    @Test
    void latePaymentAfterStockWasSoldIsFlaggedForRefund() {
        Order order = placeOrder(2);
        clock.advance(Duration.ofMinutes(20));
        expiryService.expireOverdueOrders();
        Order someoneElse = orderService.createOrder("buyer-2", "other-attempt-01", command(line("pot", 2))).order();
        assertThat(someoneElse.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        Order result = confirm(order, "pay_late");

        assertThat(result.status()).isEqualTo(OrderStatus.REFUND_REQUIRED);
        assertThat(result.gatewayPaymentId()).isEqualTo("pay_late");
        assertThat(store.product("pot").stock()).isZero();
        assertThat(store.ledgerEntries()).isEmpty();
    }

    @Test
    void aSecondDifferentPaymentForAPaidOrderDoesNotChangeIt() {
        Order order = placeOrder(1);
        confirm(order, "pay_1");

        Order result = confirm(order, "pay_2");

        assertThat(result.gatewayPaymentId()).isEqualTo("pay_1");
        assertThat(store.ledgerEntries()).hasSize(2);
    }

    @Test
    void cannotConfirmBeforeAGatewayOrderExists() {
        Order unlinked = Order.placed("manual", BUYER, placeOrder(1).items(), TestFixtures.SHIPPING,
                TestFixtures.NOW, TestFixtures.NOW.plus(Duration.ofMinutes(15)));
        store.putOrder(unlinked);

        assertThatThrownBy(() -> paymentService.confirmCheckoutPayment(BUYER, "manual", "pay_1", "00"))
                .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.PAYMENT_NOT_INITIATED));
    }
}
