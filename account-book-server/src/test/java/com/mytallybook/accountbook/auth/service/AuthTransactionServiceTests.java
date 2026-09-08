package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.support.TestTransactions;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTransactionServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-31T01:00:00Z");
    private static final String REQUEST_ID = "request-auth-transaction";

    @Mock
    private AuthStore authStore;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private MemberStore memberStore;

    private AuthTransactionService service;

    @BeforeEach
    void setUp() {
        service = new AuthTransactionService(
                authStore,
                auditLogService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new LedgerWriteGuard(authStore, memberStore)
        );
    }

    @Test
    void rejectsRemovedCurrentMembershipDespiteAnActiveLoginSnapshot() {
        AuthStore.LoginMembership stale = new AuthStore.LoginMembership(
                7L, "ACTIVE", 1L, 11L, MemberRole.MEMBER, "ACTIVE", "ACTIVE");
        AuthStore.LoginMembership removed = new AuthStore.LoginMembership(
                7L, "ACTIVE", 1L, 11L, MemberRole.MEMBER, "REMOVED", "ACTIVE");
        guardFixture();
        when(memberStore.lockLedger()).thenReturn(java.util.Optional.of(new MemberStore.LedgerState(1, 9, 10, "ACTIVE", 1)));
        when(memberStore.lockMembers()).thenReturn(java.util.List.of(
                new MemberStore.MemberState(13, 9, "ACTIVE", MemberRole.OWNER, "ACTIVE", "owner", null, NOW),
                new MemberStore.MemberState(11, 7, "ACTIVE", MemberRole.MEMBER, "REMOVED", "member", null, NOW)));
        when(authStore.lockLoginMembership(stale.userId()))
                .thenReturn(java.util.Optional.of(removed));
        IssuedSessionToken issued = new IssuedSessionToken("raw-token", "a".repeat(64), NOW.plusSeconds(3600));

        AuthResult result = TestTransactions.template().execute(status -> service.authenticate(stale.userId(), issued, REQUEST_ID));

        assertThat(result.state()).isEqualTo(AuthState.INVITE_REQUIRED);
        verify(authStore, never()).insertSession(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), any(), any());
        verify(authStore, never()).revokeAllSessions(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void rotatesSessionUpdatesLoginAndAuditsTheNewSession() {
        AuthStore.LoginMembership membership = new AuthStore.LoginMembership(
                7L,
                "ACTIVE",
                1L,
                11L,
                MemberRole.OWNER,
                "ACTIVE",
                "ACTIVE"
        );
        IssuedSessionToken issued = new IssuedSessionToken(
                "raw-token",
                "a".repeat(64),
                NOW.plusSeconds(3600)
        );
        when(authStore.insertSession(7L, "a".repeat(64), issued.expiresAt(), NOW))
                .thenReturn(91L);
        guardFixture();
        when(authStore.lockLoginMembership(7L)).thenReturn(java.util.Optional.of(membership));

        AuthResult result = TestTransactions.template().execute(status -> service.authenticate(membership.userId(), issued, REQUEST_ID));

        assertThat(result).isEqualTo(AuthResult.authenticated(issued));
        InOrder order = inOrder(authStore, memberStore, auditLogService);
        order.verify(authStore).lockAppConfig();
        order.verify(memberStore).lockLedger();
        order.verify(memberStore).lockMembers();
        order.verify(authStore).lockLoginMembership(7L);
        order.verify(authStore).revokeAllSessions(7L, NOW);
        order.verify(authStore).updateLastLogin(7L, NOW);
        order.verify(authStore).insertSession(7L, "a".repeat(64), issued.expiresAt(), NOW);

        ArgumentCaptor<AuditLogService.AuditEvent> event =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        order.verify(auditLogService).append(event.capture());
        assertThat(event.getValue().action()).isEqualTo("AUTH_LOGIN");
        assertThat(event.getValue().resourceId()).isEqualTo(91L);
        assertThat(event.getValue().details()).isEmpty();
    }

    @Test
    void revokesAndAuditsOnlyTheCurrentSession() {
        CurrentUser currentUser = new CurrentUser(7L, 1L, 11L, MemberRole.OWNER);
        when(authStore.revokeSession("b".repeat(64), NOW)).thenReturn(1);

        service.logout("b".repeat(64), currentUser, REQUEST_ID);

        verify(authStore).revokeSession("b".repeat(64), NOW);
        ArgumentCaptor<AuditLogService.AuditEvent> event =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).append(event.capture());
        assertThat(event.getValue().action()).isEqualTo("AUTH_LOGOUT");
        assertThat(event.getValue().details()).isEmpty();
        assertThat(event.getValue().details().toString()).doesNotContain("b".repeat(64));
    }

    @Test
    void doesNotCreateFalseLogoutAuditWhenSessionWasAlreadyRevoked() {
        CurrentUser currentUser = new CurrentUser(7L, 1L, 11L, MemberRole.OWNER);
        when(authStore.revokeSession("c".repeat(64), NOW)).thenReturn(0);

        service.logout("c".repeat(64), currentUser, REQUEST_ID);

        verify(auditLogService, never()).append(any());
    }

    @Test
    void logoutLocksMalformedConfigurationButStillRevokesCurrentToken() {
        org.mockito.Mockito.lenient().when(authStore.lockAppConfig())
                .thenReturn(new AuthStore.AppConfigState(false, 0, 1));
        when(authStore.revokeSession("digest", NOW)).thenReturn(1);
        service.logout("digest", new CurrentUser(7, 1, 11, MemberRole.OWNER), REQUEST_ID);
        var order = inOrder(authStore, auditLogService);
        order.verify(authStore).lockAppConfig();
        order.verify(authStore).revokeSession("digest", NOW);
        order.verify(auditLogService).append(any());
    }

    private void guardFixture() {
        when(authStore.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(memberStore.lockLedger()).thenReturn(java.util.Optional.of(new MemberStore.LedgerState(1, 7, 10, "ACTIVE", 1)));
        when(memberStore.lockMembers()).thenReturn(java.util.List.of(new MemberStore.MemberState(
                11, 7, "ACTIVE", MemberRole.OWNER, "ACTIVE", "owner", null, NOW)));
    }

    @Test
    void missingConfigurationPreventsLoginWritesWithSafeConflict() {
        when(authStore.lockAppConfig()).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestTransactions.template().execute(status -> service.authenticate(
                7, new IssuedSessionToken("raw", "a".repeat(64), NOW.plusSeconds(3600)), REQUEST_ID)))
                .isInstanceOfSatisfying(com.mytallybook.accountbook.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.mytallybook.accountbook.common.error.ErrorCode.LEDGER_STATE_CONFLICT));
        verify(authStore).lockAppConfig();
        org.mockito.Mockito.verifyNoMoreInteractions(authStore);
        org.mockito.Mockito.verifyNoInteractions(memberStore, auditLogService);
    }

    @Test
    void missingConfigurationStillFailsLogoutWithoutClaimingRevocation() {
        var missing = new org.springframework.dao.EmptyResultDataAccessException(1);
        when(authStore.lockAppConfig()).thenThrow(missing);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.logout(
                "digest", new CurrentUser(7, 1, 11, MemberRole.OWNER), REQUEST_ID)).isSameAs(missing);
        verify(authStore).lockAppConfig();
        org.mockito.Mockito.verifyNoMoreInteractions(authStore);
        org.mockito.Mockito.verifyNoInteractions(memberStore, auditLogService);
    }

    @Test
    void currentDisabledAccountTakesPrecedenceOverRemovedMembership() {
        guardFixture();
        when(authStore.lockLoginMembership(8)).thenReturn(java.util.Optional.of(new AuthStore.LoginMembership(
                8, "DISABLED", 1L, 12L, MemberRole.MEMBER, "REMOVED", "ACTIVE")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestTransactions.template().execute(status -> service.authenticate(
                8, new IssuedSessionToken("raw", "a".repeat(64), NOW.plusSeconds(3600)), REQUEST_ID)))
                .isInstanceOfSatisfying(com.mytallybook.accountbook.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.mytallybook.accountbook.common.error.ErrorCode.USER_DISABLED));
        verify(authStore, never()).insertSession(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any(), any());
        verify(authStore, never()).revokeAllSessions(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void deletedAndMissingCurrentAccountsDoNotCreateOrRevokeSessions() {
        guardFixture();
        when(authStore.lockLoginMembership(8)).thenReturn(java.util.Optional.of(new AuthStore.LoginMembership(
                8, "DELETED", 1L, 12L, MemberRole.MEMBER, "ACTIVE", "ACTIVE")), java.util.Optional.empty());
        for (int i = 0; i < 2; i++) {
            var result = TestTransactions.template().execute(status -> service.authenticate(
                    8, new IssuedSessionToken("raw", "a".repeat(64), NOW.plusSeconds(3600)), REQUEST_ID));
            assertThat(result.state()).isEqualTo(AuthState.INVITE_REQUIRED);
        }
        verify(authStore, never()).insertSession(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any(), any());
        verify(authStore, never()).revokeAllSessions(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void invalidOwnerInvariantPreventsSessionIssuance() {
        guardFixture();
        when(memberStore.lockMembers()).thenReturn(java.util.List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestTransactions.template().execute(status -> service.authenticate(
                7, new IssuedSessionToken("raw", "a".repeat(64), NOW.plusSeconds(3600)), REQUEST_ID)))
                .isInstanceOfSatisfying(com.mytallybook.accountbook.common.error.BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(com.mytallybook.accountbook.common.error.ErrorCode.LEDGER_STATE_CONFLICT));
        verify(authStore, never()).insertSession(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), any(), any());
        verify(authStore, never()).revokeAllSessions(org.mockito.ArgumentMatchers.anyLong(), any());
    }
}
