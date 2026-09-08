package com.mytallybook.accountbook.security;

import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseSessionTokenVerifierTests {

    private static final Instant NOW = Instant.parse("2026-08-31T01:00:00Z");
    private static final CurrentUser CURRENT_USER =
            new CurrentUser(7L, 1L, 11L, MemberRole.OWNER);

    @Mock
    private SessionTokenService tokenService;

    @Mock
    private AuthStore authStore;

    private DatabaseSessionTokenVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new DatabaseSessionTokenVerifier(
                tokenService,
                authStore,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void hashesRawTokenBeforeQueryingTheDatabaseAtCurrentTime() {
        String digest = "a".repeat(64);
        when(tokenService.hash("raw-token")).thenReturn(digest);
        when(authStore.findCurrentUserByTokenHash(digest, NOW))
                .thenReturn(Optional.of(CURRENT_USER));

        assertThat(verifier.verify("raw-token")).contains(CURRENT_USER);

        InOrder order = inOrder(tokenService, authStore);
        order.verify(tokenService).hash("raw-token");
        order.verify(authStore).findCurrentUserByTokenHash(digest, NOW);
        verify(authStore, never()).findCurrentUserByTokenHash("raw-token", NOW);
    }

    @Test
    void rejectsBlankTokenWithoutHashingOrQuerying() {
        assertThat(verifier.verify("  ")).isEmpty();

        verifyNoInteractions(tokenService, authStore);
    }

    @Test
    void returnsEmptyWhenNoActiveSessionMatches() {
        String digest = "b".repeat(64);
        when(tokenService.hash("expired-token")).thenReturn(digest);
        when(authStore.findCurrentUserByTokenHash(digest, NOW)).thenReturn(Optional.empty());

        assertThat(verifier.verify("expired-token")).isEmpty();
    }

    @Test
    void propagatesPersistenceFailureForTheSecurityFilterSafeErrorPath() {
        String digest = "c".repeat(64);
        when(tokenService.hash("database-failure-token")).thenReturn(digest);
        when(authStore.findCurrentUserByTokenHash(digest, NOW))
                .thenThrow(new IllegalStateException("database-secret"));

        assertThatThrownBy(() -> verifier.verify("database-failure-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database-secret");
    }
}
