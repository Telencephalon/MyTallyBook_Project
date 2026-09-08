package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.config.AuthProperties;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class BootstrapTransactionService {

    private static final long FIXED_LEDGER_ID = 1L;
    private static final String DEFAULT_NICKNAME = "微信用户";
    private static final int EXPECTED_CATEGORY_COUNT = 15;
    private static final int EXPECTED_ACCOUNT_COUNT = 4;

    private final AuthStore authStore;
    private final AuditLogService auditLogService;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final LedgerWriteGuard guard;
    private final MemberStore memberStore;

    public BootstrapTransactionService(
            AuthStore authStore,
            AuditLogService auditLogService,
            AuthProperties authProperties,
            Clock clock,
            LedgerWriteGuard guard,
            MemberStore memberStore
    ) {
        this.authStore = authStore;
        this.auditLogService = auditLogService;
        this.authProperties = authProperties;
        this.clock = clock;
        this.guard = guard;
        this.memberStore = memberStore;
    }

    @Transactional
    public AuthResult bootstrap(
            WechatIdentity identity,
            String bootstrapKey,
            IssuedSessionToken issued,
            String requestId
    ) {
        AuthStore.AppConfigState config = authStore.lockAppConfig();
        if (config.initialized()) {
            throw new BusinessException(ErrorCode.ALREADY_INITIALIZED);
        }
        if (config.maxUsers() < 1 || config.maxUsers() > 10) {
            throw new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        if (authProperties.bootstrapKey().isEmpty()) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NOT_CONFIGURED);
        }
        if (!constantTimeEquals(authProperties.bootstrapKey(), bootstrapKey)) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_KEY_INVALID);
        }

        Instant now = clock.instant();
        long userId = authStore.insertUser(
                identity.openid(),
                identity.unionid(),
                DEFAULT_NICKNAME,
                now
        );
        authStore.insertLedger(userId, config.maxUsers(), now);
        authStore.insertOwnerMembership(userId, now);

        int categoryCount = authStore.insertDefaultCategories();
        int accountCount = authStore.insertDefaultFundAccounts();
        if (categoryCount != EXPECTED_CATEGORY_COUNT || accountCount != EXPECTED_ACCOUNT_COUNT) {
            throw new IllegalStateException("Unable to create the complete default ledger data");
        }

        guard.assertOwnerInvariant(memberStore.lockLedger()
                .orElseThrow(() -> new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT)), memberStore.lockMembers());
        authStore.markInitialized(config.version(), now);
        authStore.revokeAllSessions(userId, now);
        authStore.insertSession(userId, issued.tokenHash(), issued.expiresAt(), now);
        auditLogService.append(new AuditLogService.AuditEvent(
                FIXED_LEDGER_ID,
                userId,
                "SYSTEM_BOOTSTRAP",
                "LEDGER",
                FIXED_LEDGER_ID,
                requestId,
                Map.of(
                        "categoryCount", categoryCount,
                        "accountCount", accountCount
                )
        ));
        return AuthResult.authenticated(issued);
    }

    private static boolean constantTimeEquals(String configuredKey, String suppliedKey) {
        return MessageDigest.isEqual(sha256(configuredKey), sha256(suppliedKey));
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
