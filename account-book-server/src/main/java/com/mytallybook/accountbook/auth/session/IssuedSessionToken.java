package com.mytallybook.accountbook.auth.session;

import java.time.Instant;

public record IssuedSessionToken(
        String rawToken,
        String tokenHash,
        Instant expiresAt
) {
}
