package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.config.AuthProperties;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final String BOOTSTRAP_KEY = "bootstrap-key-1234567890";
    private static final String REQUEST_ID = "request-bootstrap-1";
    private static final WechatIdentity IDENTITY = new WechatIdentity("owner-openid", "owner-unionid");
    private static final IssuedSessionToken ISSUED = new IssuedSessionToken(
            "raw-owner-token",
            "d".repeat(64),
            NOW.plus(Duration.ofDays(30))
    );

    @Mock
    private WechatSessionClient wechatSessionClient;

    @Mock
    private SessionTokenService sessionTokenService;

    @Mock
    private BootstrapTransactionService transactionService;

    @Mock
    private AuthStore authStore;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private MemberStore memberStore;

    private BootstrapService bootstrapService;

    @BeforeEach
    void setUp() {
        bootstrapService = new BootstrapService(
                wechatSessionClient,
                sessionTokenService,
                transactionService
        );
    }

    @Test
    void exchangesFreshCodeAndIssuesCandidateBeforeStartingTransaction() {
        when(wechatSessionClient.exchange("new-code")).thenReturn(IDENTITY);
        when(sessionTokenService.issue()).thenReturn(ISSUED);
        when(transactionService.bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID))
                .thenReturn(AuthResult.authenticated(ISSUED));

        AuthResult result = bootstrapService.bootstrap(
                " new-code ",
                BOOTSTRAP_KEY,
                REQUEST_ID
        );

        assertThat(result).isEqualTo(AuthResult.authenticated(ISSUED));
        InOrder order = inOrder(wechatSessionClient, sessionTokenService, transactionService);
        order.verify(wechatSessionClient).exchange("new-code");
        order.verify(sessionTokenService).issue();
        order.verify(transactionService).bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID);
    }

    @Test
    void createsExactlyOneOwnerLedgerDefaultsSessionAndSafeAuditEvent() {
        BootstrapTransactionService service = transactionService(BOOTSTRAP_KEY);
        when(authStore.lockAppConfig())
                .thenReturn(new AuthStore.AppConfigState(false, 10, 4));
        when(authStore.insertUser("owner-openid", "owner-unionid", "微信用户", NOW))
                .thenReturn(7L);
        when(authStore.insertOwnerMembership(7L, NOW)).thenReturn(11L);
        when(authStore.insertDefaultCategories()).thenReturn(15);
        when(authStore.insertDefaultFundAccounts()).thenReturn(4);
        when(authStore.insertSession(7L, "d".repeat(64), ISSUED.expiresAt(), NOW))
                .thenReturn(19L);
        when(memberStore.lockLedger()).thenReturn(java.util.Optional.of(new MemberStore.LedgerState(1, 7, 10, "ACTIVE", 0)));
        when(memberStore.lockMembers()).thenReturn(java.util.List.of(new MemberStore.MemberState(
                11, 7, "ACTIVE", MemberRole.OWNER, "ACTIVE", "微信用户", null, NOW)));

        AuthResult result = service.bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID);

        assertThat(result).isEqualTo(AuthResult.authenticated(ISSUED));
        InOrder order = inOrder(authStore, memberStore, auditLogService);
        order.verify(authStore).lockAppConfig();
        order.verify(authStore).insertUser("owner-openid", "owner-unionid", "微信用户", NOW);
        order.verify(authStore).insertLedger(7L, 10, NOW);
        order.verify(authStore).insertOwnerMembership(7L, NOW);
        order.verify(authStore).insertDefaultCategories();
        order.verify(authStore).insertDefaultFundAccounts();
        order.verify(memberStore).lockLedger();
        order.verify(memberStore).lockMembers();
        order.verify(authStore).markInitialized(4, NOW);
        order.verify(authStore).revokeAllSessions(7L, NOW);
        order.verify(authStore).insertSession(7L, "d".repeat(64), ISSUED.expiresAt(), NOW);

        ArgumentCaptor<AuditLogService.AuditEvent> auditEvent =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        order.verify(auditLogService).append(auditEvent.capture());
        assertThat(auditEvent.getValue().action()).isEqualTo("SYSTEM_BOOTSTRAP");
        assertThat(auditEvent.getValue().details())
                .isEqualTo(java.util.Map.of(
                        "categoryCount", 15,
                        "accountCount", 4
                ));
        assertThat(auditEvent.getValue().details().toString())
                .doesNotContain(BOOTSTRAP_KEY, "owner-openid", "raw-owner-token");
    }

    @Test
    void refusesToCommitBootstrapWhenCreatedOwnerInvariantIsMissing() {
        when(authStore.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(false, 10, 4));
        when(authStore.insertUser("owner-openid", "owner-unionid", "微信用户", NOW)).thenReturn(7L);
        when(authStore.insertDefaultCategories()).thenReturn(15);
        when(authStore.insertDefaultFundAccounts()).thenReturn(4);
        when(memberStore.lockLedger()).thenReturn(java.util.Optional.of(
                new MemberStore.LedgerState(1, 7, 10, "ACTIVE", 0)));
        when(memberStore.lockMembers()).thenReturn(java.util.List.of());
        assertBusinessError(() -> transactionService(BOOTSTRAP_KEY)
                .bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID), ErrorCode.LEDGER_STATE_CONFLICT);
        verify(memberStore).lockMembers();
        verify(authStore, never()).markInitialized(org.mockito.ArgumentMatchers.anyLong(), any());
        verify(authStore, never()).insertSession(org.mockito.ArgumentMatchers.anyLong(), anyString(), any(), any());
        verify(auditLogService, never()).append(any());
    }

    @Test
    void rejectsInvalidCapacityBeforeCreatingBootstrapData() {
        when(authStore.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(false, 0, 4));
        assertBusinessError(() -> transactionService(BOOTSTRAP_KEY)
                .bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID), ErrorCode.LEDGER_STATE_CONFLICT);
        verify(authStore, never()).insertUser(anyString(), any(), anyString(), any());
    }

    @Test
    void rejectsWrongMissingOrAlreadyConsumedBootstrapKeyWithoutWrites() {
        BootstrapTransactionService configured = transactionService(BOOTSTRAP_KEY);
        when(authStore.lockAppConfig())
                .thenReturn(new AuthStore.AppConfigState(false, 10, 0));

        assertBusinessError(
                () -> configured.bootstrap(IDENTITY, "wrong-key-123456789012", ISSUED, REQUEST_ID),
                ErrorCode.BOOTSTRAP_KEY_INVALID
        );
        verify(authStore, never()).insertUser(anyString(), any(), anyString(), any());

        BootstrapTransactionService missing = transactionService("");
        assertBusinessError(
                () -> missing.bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID),
                ErrorCode.BOOTSTRAP_NOT_CONFIGURED
        );

        when(authStore.lockAppConfig())
                .thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        assertBusinessError(
                () -> configured.bootstrap(IDENTITY, BOOTSTRAP_KEY, ISSUED, REQUEST_ID),
                ErrorCode.ALREADY_INITIALIZED
        );
        verify(auditLogService, never()).append(any());
    }

    private BootstrapTransactionService transactionService(String bootstrapKey) {
        return new BootstrapTransactionService(
                authStore,
                auditLogService,
                new AuthProperties(
                        bootstrapKey,
                        "test-pepper-0123456789abcdef0123456789",
                        Duration.ofDays(30),
                        32
                ),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new LedgerWriteGuard(authStore, memberStore),
                memberStore
        );
    }

    private static void assertBusinessError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }
}
