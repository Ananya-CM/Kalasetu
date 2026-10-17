package com.kalasetu.orders.api;

import com.kalasetu.orders.api.dto.ConfirmPaymentRequest;
import com.kalasetu.orders.api.dto.CreateOrderRequest;
import com.kalasetu.orders.api.dto.OrderListResponse;
import com.kalasetu.orders.api.dto.OrderResponse;
import com.kalasetu.orders.api.dto.UpdateStatusRequest;
import com.kalasetu.orders.domain.Order;
import com.kalasetu.orders.security.AuthenticatedUser;
import com.kalasetu.orders.service.ErrorCode;
import com.kalasetu.orders.service.OrderException;
import com.kalasetu.orders.service.OrderPlacement;
import com.kalasetu.orders.service.OrderService;
import com.kalasetu.orders.service.PaymentService;
import com.kalasetu.orders.service.ViewerRole;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Order API for the Kalasetu web app. Every request is authenticated by {@code FirebaseAuthenticationFilter}. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    /** {@code 201} for a new order, {@code 200} when the idempotency key replays an earlier one. */
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        OrderPlacement placement = orderService.createOrder(user.uid(), idempotencyKey, request.toCommand());
        HttpStatus status = placement.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(toResponse(placement.order()));
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String orderId) {
        return toResponse(orderService.getOrder(user.uid(), orderId));
    }

    @GetMapping
    public OrderListResponse listOrders(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @RequestParam(defaultValue = "buyer") String role,
            @RequestParam(defaultValue = "20") int limit) {
        List<OrderResponse> orders = orderService.listOrders(user.uid(), parseRole(role), limit).stream()
                .map(this::toResponse)
                .toList();
        return new OrderListResponse(orders);
    }

    @PostMapping("/{orderId}/payment")
    public OrderResponse confirmPayment(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String orderId,
            @Valid @RequestBody ConfirmPaymentRequest request) {
        Order order = paymentService.confirmCheckoutPayment(user.uid(), orderId, request.gatewayPaymentId(),
                request.signature());
        return toResponse(order);
    }

    @PatchMapping("/{orderId}/status")
    public OrderResponse updateStatus(
            @RequestAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE) AuthenticatedUser user,
            @PathVariable String orderId,
            @Valid @RequestBody UpdateStatusRequest request) {
        return toResponse(orderService.updateStatusAsSeller(user.uid(), orderId, request.status()));
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.from(order, orderService.paymentGatewayKeyId());
    }

    private static ViewerRole parseRole(String role) {
        for (ViewerRole candidate : ViewerRole.values()) {
            if (candidate.name().equalsIgnoreCase(role)) {
                return candidate;
            }
        }
        throw new OrderException(ErrorCode.VALIDATION_FAILED, "role must be 'buyer' or 'seller'.");
    }
}
