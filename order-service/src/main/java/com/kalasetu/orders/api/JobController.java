package com.kalasetu.orders.api;

import com.kalasetu.orders.config.AppProperties;
import com.kalasetu.orders.service.ErrorCode;
import com.kalasetu.orders.service.OrderException;
import com.kalasetu.orders.service.OrderExpiryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Endpoints called by Cloud Scheduler, protected by a shared secret. */
@RestController
public class JobController {

    private final OrderExpiryService orderExpiryService;
    private final byte[] jobToken;

    public JobController(OrderExpiryService orderExpiryService, AppProperties properties) {
        this.orderExpiryService = orderExpiryService;
        this.jobToken = properties.jobs().token().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/jobs/expire-orders")
    public Map<String, Integer> expireOrders(@RequestHeader(value = "X-Job-Token", required = false) String token) {
        requireValidToken(token);
        return Map.of("expired", orderExpiryService.expireOverdueOrders());
    }

    private void requireValidToken(String token) {
        boolean valid = jobToken.length > 0
                && token != null
                && MessageDigest.isEqual(jobToken, token.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new OrderException(ErrorCode.FORBIDDEN, "Invalid job token.");
        }
    }
}
