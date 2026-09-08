package com.mytallybook.accountbook.auth.service;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
public class AuthTransactionService {

    private final AuthStore authStore;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final LedgerWriteGuard guard;

    public AuthTransactionService(
            AuthStore authStore,
            AuditLogService auditLogService,
            Clock clock,
            LedgerWriteGuard guard
    ) {
        this.authStore = authStore;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.guard = guard;
    }

    @Transactional
    public AuthResult authenticate(
            long userId,
            IssuedSessionToken issued,
            String requestId
    ) {
        var locked = guard.lock();
        var currentMembership = authStore.lockLoginMembership(userId);
        if (currentMembership.isEmpty()) {
            return AuthResult.state(AuthState.INVITE_REQUIRED);
        }
        var membership = currentMembership.get();
        if ("DISABLED".equals(membership.userStatus())) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        if (!"ACTIVE".equals(membership.userStatus())
                || membership.userId() != userId
                || membership.ledgerId() == null || membership.ledgerId() != 1
                || membership.memberId() == null || membership.role() == null
                || !"ACTIVE".equals(membership.memberStatus())
                || !"ACTIVE".equals(membership.ledgerStatus())) {
            return AuthResult.state(AuthState.INVITE_REQUIRED);
        }
        locked.requireActor(new CurrentUser(userId, membership.ledgerId(), membership.memberId(), membership.role()));
        Instant now = clock.instant();
        authStore.revokeAllSessions(membership.userId(), now);
        authStore.updateLastLogin(membership.userId(), now);
        long sessionId = authStore.insertSession(
                membership.userId(),
                issued.tokenHash(),
                issued.expiresAt(),
                now
        );
        auditLogService.append(new AuditLogService.AuditEvent(
                membership.ledgerId(),
                membership.userId(),
                "AUTH_LOGIN",
                "AUTH_SESSION",
                sessionId,
                requestId,
                Map.of()
        ));
        return AuthResult.authenticated(issued);
    }

    @Transactional
    public void logout(String tokenHash, CurrentUser currentUser, String requestId) {
        authStore.lockAppConfig();
        Instant now = clock.instant();
        int revoked = authStore.revokeSession(tokenHash, now);
        if (revoked == 1) {
            auditLogService.append(new AuditLogService.AuditEvent(
                    currentUser.ledgerId(),
                    currentUser.userId(),
                    "AUTH_LOGOUT",
                    "AUTH_SESSION",
                    null,
                    requestId,
                    Map.of()
            ));
        }
    }
}
