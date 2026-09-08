package com.mytallybook.accountbook.auth.web;

import com.mytallybook.accountbook.auth.service.AuthResult;
import com.mytallybook.accountbook.auth.service.AuthState;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record AuthStateResponse(
        AuthState state,
        String token,
        OffsetDateTime expiresAt
) {

    public static AuthStateResponse from(AuthResult result) {
        OffsetDateTime expiresAt = result.expiresAt() == null
                ? null
                : result.expiresAt().atOffset(ZoneOffset.UTC);
        return new AuthStateResponse(result.state(), result.token(), expiresAt);
    }
}
