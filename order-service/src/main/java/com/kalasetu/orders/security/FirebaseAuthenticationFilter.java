package com.kalasetu.orders.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Requires a valid Firebase ID token ({@code Authorization: Bearer <token>}) on every request it is
 * registered for, and exposes the caller as a request attribute.
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_BODY = """
            {"type":"about:blank","title":"Unauthorized","status":401,\
            "detail":"A valid Firebase ID token is required.","code":"UNAUTHENTICATED"}""";

    private final IdTokenVerifier idTokenVerifier;

    public FirebaseAuthenticationFilter(IdTokenVerifier idTokenVerifier) {
        this.idTokenVerifier = idTokenVerifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight requests never carry credentials.
        return HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX) || header.length() == BEARER_PREFIX.length()) {
            rejectUnauthenticated(response);
            return;
        }
        AuthenticatedUser user;
        try {
            user = idTokenVerifier.verify(header.substring(BEARER_PREFIX.length()).trim());
        } catch (InvalidIdTokenException e) {
            log.debug("Rejected ID token: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            rejectUnauthenticated(response);
            return;
        }
        request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private static void rejectUnauthenticated(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
