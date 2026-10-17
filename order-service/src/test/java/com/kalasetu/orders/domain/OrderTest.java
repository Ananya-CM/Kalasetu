package com.kalasetu.orders.domain;

import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = TestFixtures.NOW;

    private static Order twoSellerOrder() {
        List<OrderItem> items = List.of(
                new OrderItem("p1", "sellerA", "Clay pot", 50_000, 2),
                new OrderItem("p2", "sellerB", "Silk scarf", 120_000, 1),
                new OrderItem("p3", "sellerA", "Lamp", 30_000, 1));
        return Order.placed("o1", "buyer", items, TestFixtures.SHIPPING, NOW, NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void placedOrderComputesTotalAndAwaitsPayment() {
        Order order = twoSellerOrder();

        assertThat(order.totalPaise()).isEqualTo(250_000);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.statusHistory()).singleElement()
                .extracting(StatusChange::actor).isEqualTo("buyer:buyer");
    }

    @Test
    void splitsTotalsPerSeller() {
        Order order = twoSellerOrder();

        assertThat(order.sellerIds()).containsExactly("sellerA", "sellerB");
        assertThat(order.totalsBySeller()).containsExactlyEntriesOf(Map.of("sellerA", 130_000L, "sellerB", 120_000L));
    }

    @Test
    void transitionAppendsHistoryWithoutChangingTheOriginal() {
        Order order = twoSellerOrder();
        Instant later = NOW.plusSeconds(60);

        Order paid = order.transitionTo(OrderStatus.PAID, later, "webhook");

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.updatedAt()).isEqualTo(later);
        assertThat(paid.statusHistory()).extracting(StatusChange::status)
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> twoSellerOrder().transitionTo(OrderStatus.DELIVERED, NOW, "seller:sellerA"))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING_PAYMENT")
                .hasMessageContaining("DELIVERED");
    }

    @Test
    void visibilityCoversBuyerAndSellersOnly() {
        Order order = twoSellerOrder();

        assertThat(order.isVisibleTo("buyer")).isTrue();
        assertThat(order.isVisibleTo("sellerB")).isTrue();
        assertThat(order.isVisibleTo("stranger")).isFalse();
    }

    @Test
    void expiresExactlyAtTheReservationDeadline() {
        Order order = twoSellerOrder();

        assertThat(order.isExpiredAt(NOW.plus(Duration.ofMinutes(15)).minusMillis(1))).isFalse();
        assertThat(order.isExpiredAt(NOW.plus(Duration.ofMinutes(15)))).isTrue();
        assertThat(order.transitionTo(OrderStatus.PAID, NOW, "webhook").isExpiredAt(NOW.plus(Duration.ofDays(1))))
                .isFalse();
    }

    @Test
    void rejectsTotalThatDoesNotMatchItems() {
        Order order = twoSellerOrder();

        assertThatThrownBy(() -> new Order(order.id(), order.buyerId(), order.items(), order.totalPaise() + 1,
                order.currency(), order.shipping(), order.status(), null, null, order.statusHistory(),
                order.createdAt(), order.updatedAt(), order.expiresAt()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyOrder() {
        assertThatThrownBy(() -> Order.placed("o1", "buyer", List.of(), TestFixtures.SHIPPING, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
