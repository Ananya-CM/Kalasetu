package com.kalasetu.orders.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.stereotype.Component;

@Component
public class FirebaseIdTokenVerifier implements IdTokenVerifier {

    private final FirebaseAuth firebaseAuth;

    public FirebaseIdTokenVerifier(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public AuthenticatedUser verify(String idToken) {
        try {
            FirebaseToken token = firebaseAuth.verifyIdToken(idToken);
            return new AuthenticatedUser(token.getUid(), token.getEmail());
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            throw new InvalidIdTokenException("Firebase ID token rejected", e);
        }
    }
}
