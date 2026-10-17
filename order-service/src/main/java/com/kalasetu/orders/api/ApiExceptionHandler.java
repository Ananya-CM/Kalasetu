package com.kalasetu.orders.api;

import com.kalasetu.orders.domain.InvalidStatusTransitionException;
import com.kalasetu.orders.payment.PaymentGatewayException;
import com.kalasetu.orders.service.ErrorCode;
import com.kalasetu.orders.service.OrderException;
import com.kalasetu.orders.store.StoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps failures to RFC 7807 problem details with a stable {@code code} property. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderException.class)
    ProblemDetail handleOrderException(OrderException e) {
        return problem(statusFor(e.code()), e.code(), e.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidStatusTransitionException e) {
        return problem(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request is invalid.");
        detail.setProperty("fieldErrors", fieldErrors);
        return detail;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException e) {
        ErrorCode code = OrderController.IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.INVALID_IDEMPOTENCY_KEY
                : ErrorCode.VALIDATION_FAILED;
        return problem(HttpStatus.BAD_REQUEST, code, "Missing required header " + e.getHeaderName() + ".");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleUnreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request could not be read.");
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ProblemDetail handleGatewayFailure(PaymentGatewayException e) {
        log.error("Payment gateway call failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Payments are temporarily unavailable. Please retry.");
    }

    @ExceptionHandler(StoreException.class)
    ProblemDetail handleStoreFailure(StoreException e) {
        log.error("Database call failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.STORE_UNAVAILABLE,
                "The service is temporarily unavailable. Please retry.");
    }

    static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, INVALID_IDEMPOTENCY_KEY, INVALID_PAYMENT_SIGNATURE, STATUS_NOT_SETTABLE,
                 INVALID_WEBHOOK_SIGNATURE -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, NOT_ORDER_SELLER -> HttpStatus.FORBIDDEN;
            case ORDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case OUT_OF_STOCK, PAYMENT_NOT_INITIATED, INVALID_STATUS_TRANSITION -> HttpStatus.CONFLICT;
            case IDEMPOTENCY_KEY_REUSED, PRODUCT_NOT_FOUND -> HttpStatus.UNPROCESSABLE_CONTENT;
            case PAYMENT_GATEWAY_UNAVAILABLE, STORE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code.name());
        return detail;
    }
}
