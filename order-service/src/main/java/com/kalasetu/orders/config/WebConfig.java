package com.kalasetu.orders.config;

import com.kalasetu.orders.security.FirebaseAuthenticationFilter;
import com.kalasetu.orders.security.IdTokenVerifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * Servlet filters, in order: CORS first (so even 401 responses carry CORS headers and the browser
 * can read them), then Firebase authentication for the public order API.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig {

    static final String ORDER_API_PATTERN = "/api/v1/orders/*";
    static final String ORDER_API_ROOT = "/api/v1/orders";

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/orders/**", configuration);
        source.registerCorsConfiguration(ORDER_API_ROOT, configuration);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<FirebaseAuthenticationFilter> firebaseAuthenticationFilter(
            IdTokenVerifier idTokenVerifier) {
        FilterRegistrationBean<FirebaseAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new FirebaseAuthenticationFilter(idTokenVerifier));
        registration.addUrlPatterns(ORDER_API_ROOT, ORDER_API_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
