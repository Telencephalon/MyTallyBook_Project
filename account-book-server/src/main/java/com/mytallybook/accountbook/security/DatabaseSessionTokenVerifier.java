package com.mytallybook.accountbook.security;

import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

@Component
public final class DatabaseSessionTokenVerifier implements SessionTokenVerifier {

    private final SessionTokenService tokenService;
    private final AuthStore authStore;
    private final Clock clock;

    public DatabaseSessionTokenVerifier(
            SessionTokenService tokenService,
            AuthStore authStore,
            Clock clock
    ) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
        this.authStore = Objects.requireNonNull(authStore, "authStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<CurrentUser> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = tokenService.hash(rawToken);
        return authStore.findCurrentUserByTokenHash(tokenHash, clock.instant());
    }
}
