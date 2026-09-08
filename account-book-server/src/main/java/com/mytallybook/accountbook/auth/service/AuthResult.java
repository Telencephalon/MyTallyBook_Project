package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.auth.session.IssuedSessionToken;

import java.time.Instant;
import java.util.Objects;

public record AuthResult(
        AuthState state,
        String token,
        Instant expiresAt
) {

    public AuthResult {
        Objects.requireNonNull(state, "state must not be null");
        if (state == AuthState.AUTHENTICATED) {
            Objects.requireNonNull(token, "authenticated result requires token");
            Objects.requireNonNull(expiresAt, "authenticated result requires expiresAt");
        } else if (token != null || expiresAt != null) {
            throw new IllegalArgumentException("unauthenticated result must not expose a token");
        }
    }

    public static AuthResult state(AuthState state) {
        if (state == AuthState.AUTHENTICATED) {
            throw new IllegalArgumentException("authenticated state requires an issued token");
        }
        return new AuthResult(state, null, null);
    }

    public static AuthResult authenticated(IssuedSessionToken issued) {
        Objects.requireNonNull(issued, "issued must not be null");
        return new AuthResult(AuthState.AUTHENTICATED, issued.rawToken(), issued.expiresAt());
    }
}
