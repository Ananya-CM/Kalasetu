package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
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

class OrderExpiryServiceTest {

    private InMemoryOrderStore store;
    private MutableClock clock;
    private OrderService orderService;
    private OrderExpiryService expiryService;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        clock = new MutableClock(TestFixtures.NOW);
        orderService = new OrderService(store, new FakePaymentGateway(), clock, TestFixtures.properties());
        expiryService = new OrderExpiryService(store, clock, TestFixtures.properties());
        store.putProduct(new Product("pot", "seller-a", "Terracotta pot", 45_000, 5L));
        store.putProduct(new Product("scarf", "seller-b", "Silk scarf", 100_000, null));
    }

    @Test
    void leavesOrdersAloneUntilTheirReservationEnds() {
        orderService.createOrder("buyer-1", "attempt-00001", command(line("pot", 2)));
        clock.advance(TestFixtures.RESERVATION_TTL.minusSeconds(1));

        assertThat(expiryService.expireOverdueOrders()).isZero();
        assertThat(store.product("pot").stock()).isEqualTo(3L);
    }

    @Test
    void expiresUnpaidOrdersAndReturnsTrackedStock() {
        Order order = orderService.createOrder("buyer-1", "attempt-00001", command(line("pot", 2), line("scarf", 1)))
                .order();
        clock.advance(TestFixtures.RESERVATION_TTL.plusSeconds(1));

        assertThat(expiryService.expireOverdueOrders()).isEqualTo(1);

        Order expired = store.findOrder(order.id()).orElseThrow();
        assertThat(expired.status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(expired.statusHistory().getLast().actor()).isEqualTo("system");
        assertThat(store.product("pot").stock()).isEqualTo(5L);
        assertThat(store.product("scarf").stock()).isNull();
    }

    @Test
    void neverExpiresPaidOrders() {
        Order order = orderService.createOrder("buyer-1", "attempt-00001", command(line("pot", 1))).order();
        store.putOrder(order.withGatewayPaymentId("pay_1", TestFixtures.NOW)
                .transitionTo(OrderStatus.PAID, TestFixtures.NOW, "webhook"));
        clock.advance(Duration.ofHours(1));

        assertThat(expiryService.expireOverdueOrders()).isZero();
        assertThat(store.product("pot").stock()).isEqualTo(4L);
    }

    @Test
    void runningTwiceDoesNotReturnStockTwice() {
        orderService.createOrder("buyer-1", "attempt-00001", command(line("pot", 2)));
        clock.advance(Duration.ofHours(1));

        expiryService.expireOverdueOrders();
        expiryService.expireOverdueOrders();

        assertThat(store.product("pot").stock()).isEqualTo(5L);
    }
}
