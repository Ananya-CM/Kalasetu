package com.kalasetu.orders.api;

import com.kalasetu.orders.config.WebConfig;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.domain.OrderItem;
import com.kalasetu.orders.domain.OrderStatus;
import com.kalasetu.orders.security.AuthenticatedUser;
import com.kalasetu.orders.security.IdTokenVerifier;
import com.kalasetu.orders.security.InvalidIdTokenException;
import com.kalasetu.orders.service.ErrorCode;
import com.kalasetu.orders.service.OrderException;
import com.kalasetu.orders.service.OrderPlacement;
import com.kalasetu.orders.service.OrderService;
import com.kalasetu.orders.service.PaymentService;
import com.kalasetu.orders.service.ViewerRole;
import com.kalasetu.orders.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class, properties = {
        "kalasetu.razorpay.key-id=rzp_test_key",
        "kalasetu.razorpay.key-secret=secret",
        "kalasetu.razorpay.webhook-secret=webhook-secret",
        "kalasetu.cors.allowed-origins=https://kalasetu-25a17.web.app"
})
@Import(WebConfig.class)
class OrderControllerTest {

    private static final String TOKEN = "valid-token";
    private static final String BUYER = "buyer-1";
    private static final String VALID_BODY = """
            {"items":[{"productId":"pot","quantity":2}],
             "shipping":{"name":"Asha Rao","email":"asha@example.com","address":"12 MG Road, Bengaluru"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IdTokenVerifier idTokenVerifier;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        when(idTokenVerifier.verify(TOKEN)).thenReturn(new AuthenticatedUser(BUYER, "asha@example.com"));
        when(idTokenVerifier.verify("bad-token"))
                .thenThrow(new InvalidIdTokenException("rejected", new RuntimeException("expired")));
        when(orderService.paymentGatewayKeyId()).thenReturn("rzp_test_key");
    }

    private static Order pendingOrder() {
        return Order.placed("order1", BUYER, List.of(new OrderItem("pot", "seller-1", "Terracotta pot", 45_000, 2)),
                        TestFixtures.SHIPPING, TestFixtures.NOW, TestFixtures.NOW.plus(Duration.ofMinutes(15)))
                .withGatewayOrderId("order_rzp_1", TestFixtures.NOW);
    }

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(orderService, never()).createOrder(anyString(), anyString(), any());
    }

    @Test
    void rejectsInvalidTokens() throws Exception {
        mockMvc.perform(get("/api/v1/orders/order1").header(HttpHeaders.AUTHORIZATION, "Bearer bad-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedResponsesStillCarryCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/orders/order1").header(HttpHeaders.ORIGIN, "https://kalasetu-25a17.web.app"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://kalasetu-25a17.web.app"));
    }

    @Test
    void corsPreflightNeedsNoToken() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "https://kalasetu-25a17.web.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://kalasetu-25a17.web.app"));
    }

    @Test
    void unknownOriginsAreRefused() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createsOrderWith201AndReturnsCheckoutDetails() throws Exception {
        when(orderService.createOrder(eq(BUYER), eq("attempt-00001"), any()))
                .thenReturn(new OrderPlacement(pendingOrder(), false));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", "attempt-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("order1"))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalPaise").value(90_000))
                .andExpect(jsonPath("$.gatewayOrderId").value("order_rzp_1"))
                .andExpect(jsonPath("$.gatewayKeyId").value("rzp_test_key"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-17T10:15:00Z"));
    }

    @Test
    void replayedOrderReturns200() throws Exception {
        when(orderService.createOrder(eq(BUYER), eq("attempt-00001"), any()))
                .thenReturn(new OrderPlacement(pendingOrder(), true));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", "attempt-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void paidOrdersDoNotExposeCheckoutDetails() throws Exception {
        Order paid = pendingOrder().withGatewayPaymentId("pay_1", TestFixtures.NOW)
                .transitionTo(OrderStatus.PAID, TestFixtures.NOW, "webhook");
        when(orderService.getOrder(BUYER, "order1")).thenReturn(paid);

        mockMvc.perform(get("/api/v1/orders/order1").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.gatewayKeyId").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        String body = """
                {"items":[{"productId":"","quantity":0}],
                 "shipping":{"name":"Asha","email":"not-an-email","address":""}}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", "attempt-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors['shipping.email']").exists())
                .andExpect(jsonPath("$.fieldErrors['items[0].quantity']").exists());
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", "attempt-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void businessErrorsMapToTheirStatus() throws Exception {
        when(orderService.createOrder(eq(BUYER), anyString(), any()))
                .thenThrow(new OrderException(ErrorCode.OUT_OF_STOCK, "Only 1 left of Terracotta pot."));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header("Idempotency-Key", "attempt-00001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.detail").value("Only 1 left of Terracotta pot."));
    }

    @Test
    void listUsesTheRequestedRole() throws Exception {
        when(orderService.listOrders(BUYER, ViewerRole.SELLER, 10)).thenReturn(List.of(pendingOrder()));

        mockMvc.perform(get("/api/v1/orders").param("role", "seller").param("limit", "10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].orderId").value("order1"));
    }

    @Test
    void unknownRoleIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("role", "admin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest());
        verify(orderService, never()).listOrders(anyString(), any(), anyInt());
    }

    @Test
    void confirmPaymentPassesThroughTheCaller() throws Exception {
        Order paid = pendingOrder().withGatewayPaymentId("pay_1", TestFixtures.NOW)
                .transitionTo(OrderStatus.PAID, TestFixtures.NOW, "buyer:" + BUYER);
        when(paymentService.confirmCheckoutPayment(BUYER, "order1", "pay_1", "abc123")).thenReturn(paid);

        mockMvc.perform(post("/api/v1/orders/order1/payment")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayPaymentId\":\"pay_1\",\"signature\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void unknownStatusValueIsABadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/orders/order1/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest());
    }
}
