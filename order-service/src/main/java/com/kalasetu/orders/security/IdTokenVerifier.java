package com.kalasetu.orders.security;

public interface IdTokenVerifier {

    /**
     * @throws InvalidIdTokenException if the token is malformed, expired, or not issued for this project
     */
    AuthenticatedUser verify(String idToken);
}
