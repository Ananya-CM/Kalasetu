package com.kalasetu.orders.service;

import com.kalasetu.orders.domain.InvalidStatusTransitionException;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.domain.Product;
import com.kalasetu.orders.domain.ShippingDetails;
import com.kalasetu.orders.payment.PaymentGatewayException;
import com.kalasetu.orders.testsupport.FakePaymentGateway;
import com.kalasetu.orders.testsupport.InMemoryOrderStore;
import com.kalasetu.orders.testsupport.MutableClock;
import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.kalasetu.orders.testsupport.TestFixtures.command;
import static com.kalasetu.orders.testsupport.TestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderServiceTest {

    private static final String BUYER = "buyer-1";
    private static final String SELLER = "seller-1";
    private static final String KEY = "checkout-attempt-0001";

    private InMemoryOrderStore store;
    private FakePaymentGateway gateway;
    private MutableClock clock;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        gateway = new FakePaymentGateway();
        clock = new MutableClock(TestFixtures.NOW);
        orderService = new OrderService(store, gateway, clock, TestFixtures.properties());
        store.putProduct(new Product("pot", SELLER, "Terracotta pot", 45_000, 5L));
        store.putProduct(new Product("scarf", "seller-2", "Silk scarf", 129_950, null));
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((OrderException) thrown).code();
    }

    @Nested
    class CreateOrder {

        @Test
        void pricesComeFromTheDatabaseAndStockIsReserved() {
            OrderPlacement placement = orderService.createOrder(BUYER, KEY, command(line("pot", 2), line("scarf", 1)));

            Order order = placement.order();
            assertThat(placement.replayed()).isFalse();
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.totalPaise()).isEqualTo(2 * 45_000 + 129_950);
            assertThat(order.expiresAt()).isEqualTo(TestFixtures.NOW.plus(TestFixtures.RESERVATION_TTL));
            assertThat(order.gatewayOrderId()).isEqualTo("order_" + order.id());
            assertThat(store.product("pot").stock()).isEqualTo(3L);
        }

        @Test
        void productsWithoutStockTrackingAreNotLimited() {
            orderService.createOrder(BUYER, KEY, command(line("scarf", 100)));

            assertThat(store.product("scarf").stock()).isNull();
        }

        @Test
        void repeatedProductLinesAreMergedBeforeCheckingStock() {
            Order order = orderService.createOrder(BUYER, KEY, command(line("pot", 2), line("pot", 3))).order();

            assertThat(order.items()).singleElement().satisfies(item -> assertThat(item.quantity()).isEqualTo(5));
            assertThat(store.product("pot").stock()).isZero();
        }

        @Test
        void rejectsWhenStockIsInsufficientAndReservesNothing() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("scarf", 1), line("pot", 6))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.OUT_OF_STOCK));

            assertThat(store.product("pot").stock()).isEqualTo(5L);
            assertThat(store.orderCount()).isZero();
        }

        @Test
        void rejectsUnknownProduct() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("ghost", 1))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        }

        @Test
        void rejectsProductIdsThatCouldEscapeTheCollection() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("pot/reviews/x", 1))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
            assertThat(store.transactionCount()).isZero();
        }

        @Test
        void rejectsTooManyUnitsOfOneProductEvenAcrossLines() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY,
                    command(line("scarf", 60), line("scarf", 41))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        void rejectsEmptyOrderAndInvalidQuantity() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command()))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("pot", 0))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        void rejectsInvalidIdempotencyKey() {
            assertThatThrownBy(() -> orderService.createOrder(BUYER, "bad key", command(line("pot", 1))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.INVALID_IDEMPOTENCY_KEY));
        }
    }

    @Nested
    class Idempotency {

        @Test
        void retryWithSameKeyReturnsTheSameOrderWithoutReservingTwice() {
            Order first = orderService.createOrder(BUYER, KEY, command(line("pot", 2))).order();

            OrderPlacement retry = orderService.createOrder(BUYER, KEY, command(line("pot", 2)));

            assertThat(retry.replayed()).isTrue();
            assertThat(retry.order().id()).isEqualTo(first.id());
            assertThat(store.orderCount()).isEqualTo(1);
            assertThat(store.product("pot").stock()).isEqualTo(3L);
            assertThat(gateway.createdCount()).isEqualTo(1);
        }

        @Test
        void sameKeyForADifferentRequestIsRejected() {
            orderService.createOrder(BUYER, KEY, command(line("pot", 2)));

            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("pot", 3))))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        }

        @Test
        void sameKeyFromAnotherUserCreatesASeparateOrder() {
            Order mine = orderService.createOrder(BUYER, KEY, command(line("pot", 1))).order();
            Order theirs = orderService.createOrder("buyer-2", KEY, command(line("pot", 1))).order();

            assertThat(theirs.id()).isNotEqualTo(mine.id());
            assertThat(theirs.buyerId()).isEqualTo("buyer-2");
        }

        @Test
        void gatewayOutageKeepsTheOrderAndARetryCompletesIt() {
            gateway.failNextCalls(1);

            assertThatThrownBy(() -> orderService.createOrder(BUYER, KEY, command(line("pot", 1))))
                    .isInstanceOf(PaymentGatewayException.class);
            assertThat(store.orderCount()).isEqualTo(1);
            assertThat(store.product("pot").stock()).isEqualTo(4L);

            OrderPlacement retry = orderService.createOrder(BUYER, KEY, command(line("pot", 1)));

            assertThat(retry.replayed()).isTrue();
            assertThat(retry.order().gatewayOrderId()).isNotNull();
            assertThat(store.product("pot").stock()).isEqualTo(4L);
        }

        @Test
        void replayOfAPaidOrderDoesNotCallTheGatewayAgain() {
            Order order = orderService.createOrder(BUYER, KEY, command(line("pot", 1))).order();
            store.putOrder(order.withGatewayPaymentId("pay_1", TestFixtures.NOW)
                    .transitionTo(OrderStatus.PAID, TestFixtures.NOW, "webhook"));

            OrderPlacement replay = orderService.createOrder(BUYER, KEY, command(line("pot", 1)));

            assertThat(replay.order().status()).isEqualTo(OrderStatus.PAID);
            assertThat(gateway.createdCount()).isEqualTo(1);
        }
    }

    @Nested
    class Concurrency {

        @Test
        void onlyOneOfManyBuyersRacingForTheLastUnitSucceeds() throws Exception {
            store.putProduct(new Product("last", SELLER, "One-of-a-kind mask", 250_000, 1L));
            int buyers = 16;
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < buyers; i++) {
                String buyerId = "racer-" + i;
                attempts.add(() -> {
                    start.await();
                    try {
                        orderService.createOrder(buyerId, UUID.randomUUID().toString(), command(line("last", 1)));
                        return true;
                    } catch (OrderException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.OUT_OF_STOCK);
                        return false;
                    }
                });
            }

            List<Future<Boolean>> results;
            try (ExecutorService executor = Executors.newFixedThreadPool(buyers)) {
                List<Future<Boolean>> submitted = attempts.stream().map(executor::submit).toList();
                start.countDown();
                results = submitted;
            }

            long successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(store.product("last").stock()).isZero();
            assertThat(store.orderCount()).isEqualTo(1);
        }
    }

    @Nested
    class ReadingAndFulfilment {

        private Order paidOrder() {
            Order order = orderService.createOrder(BUYER, KEY, command(line("pot", 1))).order();
            Order paid = order.withGatewayPaymentId("pay_1", TestFixtures.NOW)
                    .transitionTo(OrderStatus.PAID, TestFixtures.NOW, "webhook");
            store.putOrder(paid);
            return paid;
        }

        @Test
        void buyerAndSellerCanReadButOthersGetNotFound() {
            Order order = paidOrder();

            assertThat(orderService.getOrder(BUYER, order.id()).id()).isEqualTo(order.id());
            assertThat(orderService.getOrder(SELLER, order.id()).id()).isEqualTo(order.id());
            assertThatThrownBy(() -> orderService.getOrder("stranger", order.id()))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
            assertThatThrownBy(() -> orderService.getOrder(BUYER, "../products/pot"))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        void listsByRole() {
            Order order = paidOrder();

            assertThat(orderService.listOrders(BUYER, ViewerRole.BUYER, 20)).extracting(Order::id)
                    .containsExactly(order.id());
            assertThat(orderService.listOrders(SELLER, ViewerRole.SELLER, 20)).extracting(Order::id)
                    .containsExactly(order.id());
            assertThat(orderService.listOrders(SELLER, ViewerRole.BUYER, 20)).isEmpty();
        }

        @Test
        void sellerShipsThenDelivers() {
            Order order = paidOrder();

            orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.SHIPPED);
            Order delivered = orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.DELIVERED);

            assertThat(delivered.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(delivered.statusHistory().getLast().actor()).isEqualTo("seller:" + SELLER);
        }

        @Test
        void repeatingTheCurrentStatusIsANoOp() {
            Order order = paidOrder();
            orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.SHIPPED);

            Order again = orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.SHIPPED);

            assertThat(again.statusHistory()).hasSize(3);
        }

        @Test
        void cannotShipAnUnpaidOrder() {
            Order order = orderService.createOrder(BUYER, KEY, command(line("pot", 1))).order();

            assertThatThrownBy(() -> orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.SHIPPED))
                    .isInstanceOf(InvalidStatusTransitionException.class);
        }

        @Test
        void buyerCannotUpdateFulfilment() {
            Order order = paidOrder();

            assertThatThrownBy(() -> orderService.updateStatusAsSeller(BUYER, order.id(), OrderStatus.SHIPPED))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.NOT_ORDER_SELLER));
        }

        @Test
        void sellerCannotSetPaymentStatuses() {
            Order order = paidOrder();

            assertThatThrownBy(() -> orderService.updateStatusAsSeller(SELLER, order.id(), OrderStatus.PAID))
                    .satisfies(thrown -> assertThat(codeOf(thrown)).isEqualTo(ErrorCode.STATUS_NOT_SETTABLE));
        }

        @Test
        void shippingDetailsAreStoredAsGiven() {
            ShippingDetails shipping = new ShippingDetails("Ravi", "ravi@example.com", "Mysuru");
            Order order = orderService.createOrder(BUYER, KEY,
                    new CreateOrderCommand(List.of(line("pot", 1)), shipping)).order();

            assertThat(store.findOrder(order.id()).orElseThrow().shipping()).isEqualTo(shipping);
        }
    }
}
