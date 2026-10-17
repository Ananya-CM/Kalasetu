package com.kalasetu.orders.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT, PAID",
            "PENDING_PAYMENT, EXPIRED",
            "EXPIRED, PAID",
            "EXPIRED, REFUND_REQUIRED",
            "PAID, SHIPPED",
            "SHIPPED, DELIVERED"
    })
    void allowsLifecycleTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT, SHIPPED",
            "PENDING_PAYMENT, DELIVERED",
            "PAID, PENDING_PAYMENT",
            "PAID, DELIVERED",
            "PAID, EXPIRED",
            "SHIPPED, PAID",
            "DELIVERED, SHIPPED",
            "DELIVERED, PAID",
            "REFUND_REQUIRED, PAID",
            "EXPIRED, SHIPPED"
    })
    void rejectsSkippedOrBackwardTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void noStatusTransitionsToItself() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canTransitionTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    void sellersCanOnlySetFulfilmentStatuses() {
        assertThat(OrderStatus.SHIPPED.isSellerSettable()).isTrue();
        assertThat(OrderStatus.DELIVERED.isSellerSettable()).isTrue();
        assertThat(OrderStatus.PAID.isSellerSettable()).isFalse();
        assertThat(OrderStatus.REFUND_REQUIRED.isSellerSettable()).isFalse();
    }
}
