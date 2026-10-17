package com.kalasetu.orders.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kalasetu.orders.config.AppProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.Map;

/** Razorpay Orders API client. See https://razorpay.com/docs/api/orders/ */
@Component
public class RazorpayGateway implements PaymentGateway {

    private final RestClient restClient;
    private final String keyId;

    public RazorpayGateway(RestClient.Builder restClientBuilder, AppProperties properties) {
        AppProperties.Razorpay razorpay = properties.razorpay();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(razorpay.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(razorpay.readTimeout());
        this.restClient = restClientBuilder
                .baseUrl(razorpay.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(razorpay.keyId(), razorpay.keySecret()))
                .build();
        this.keyId = razorpay.keyId();
    }

    @Override
    public GatewayOrder createOrder(String receipt, long amountPaise, String currency) {
        Map<String, Object> body = Map.of(
                "amount", amountPaise,
                "currency", currency,
                "receipt", receipt,
                "notes", Map.of("kalasetuOrderId", receipt));
        RazorpayOrderResponse response;
        try {
            response = restClient.post()
                    .uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RazorpayOrderResponse.class);
        } catch (RestClientException e) {
            throw new PaymentGatewayException("Razorpay order creation failed", e);
        }
        if (response == null || response.id() == null) {
            throw new PaymentGatewayException("Razorpay returned an empty order");
        }
        if (response.amount() != amountPaise) {
            throw new PaymentGatewayException("Razorpay order amount " + response.amount()
                    + " does not match requested " + amountPaise);
        }
        return new GatewayOrder(response.id(), response.amount(), response.currency());
    }

    @Override
    public String publicKeyId() {
        return keyId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RazorpayOrderResponse(String id, long amount, String currency) {
    }
}
