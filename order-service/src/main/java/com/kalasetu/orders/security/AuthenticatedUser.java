package com.kalasetu.orders.security;

import java.util.Objects;

/** The caller, as identified by a verified Firebase ID token. */
public record AuthenticatedUser(String uid, String email) {

    /** Request attribute under which the authentication filter stores the caller. */
    public static final String REQUEST_ATTRIBUTE = "kalasetu.authenticatedUser";

    public AuthenticatedUser {
        Objects.requireNonNull(uid, "uid");
    }
}
