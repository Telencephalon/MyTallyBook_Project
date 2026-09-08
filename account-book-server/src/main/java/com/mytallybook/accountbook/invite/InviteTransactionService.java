package com.mytallybook.accountbook.invite;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.service.AuthResult;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.common.error.*;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.invite.store.InviteStore.InviteRow;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class InviteTransactionService {

    private final LedgerWriteGuard guard;
    private final AuthStore auth;
    private final MemberStore members;
    private final InviteStore invites;
    private final AuditLogService audit;
    private final Clock clock;

    public InviteTransactionService(LedgerWriteGuard guard, AuthStore auth, MemberStore members,
                                    InviteStore invites, AuditLogService audit, Clock clock) {
        this.guard = guard;
        this.auth = auth;
        this.members = members;
        this.invites = invites;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public CreatedInvite create(CurrentUser actor, int hours, String raw, String hash, String requestId) {
        var locked = guard.lock();
        requireManager(locked.requireActor(actor));
        Instant now = now(),
                expiry = now.plus(hours, ChronoUnit.HOURS);
        long id = InviteService.requireSafeId(invites.insert(hash, actor.userId(), now, expiry));
        append(actor.userId(), "INVITE_CREATE", id, requestId,
                Map.of("expiresInHours", hours, "expiresAt", expiry.toString()));
        return new CreatedInvite(id, raw, expiry, "ACTIVE");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvitePage list(CurrentUser actor, int page, int pageSize, int offset, String status) {
        AuthStore.AppConfigState config;
        try {
            config = auth.readAppConfig();
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        var ledger = members.readLedger()
                .orElseThrow(() -> new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT));
        if (!config.initialized() || config.maxUsers() < 1 || config.maxUsers() > 10 || ledger.id() != 1
                || !"ACTIVE".equals(ledger.status()) || ledger.maxMembers() < 1 || ledger.maxMembers() > 10) {
            throw new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        var snapshot = members.readMembers();
        guard.assertOwnerInvariant(ledger, snapshot);
        requireManager(new LedgerWriteGuard.LockedLedger(config, ledger, snapshot).requireActor(actor));
        Instant now = now();
        long total = invites.count(status, now);
        var items = invites.list(status, now, pageSize, offset);
        items.forEach(row -> {
            InviteService.requireSafeId(row.id());
            InviteService.requireSafeId(row.createdBy());
            if (row.usedBy() != null) {
                InviteService.requireSafeId(row.usedBy());
            }
        });
        return new InvitePage(items, page, pageSize, total);
    }

    @Transactional
    public RevokedInvite revoke(CurrentUser actor, long id, String requestId) {
        var locked = guard.lock();
        requireManager(locked.requireActor(actor));
        var row = invites.lockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (row.ledgerId() != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = now();
        if ("REVOKED".equals(row.status())) {
            return new RevokedInvite(id, "REVOKED");
        }
        String status = effective(row, locked, now);
        if ("USED".equals(status)) {
            throw new BusinessException(ErrorCode.INVITE_USED);
        }
        if ("EXPIRED".equals(status)) {
            throw new BusinessException(ErrorCode.INVITE_EXPIRED);
        }
        changed(invites.revoke(id, now));
        append(actor.userId(), "INVITE_REVOKE", id, requestId, Map.of("reason", "EXPLICIT"));
        return new RevokedInvite(id, "REVOKED");
    }

    @Transactional
    public AuthResult accept(WechatIdentity identity, String hash, IssuedSessionToken session, String requestId) {
        var locked = guard.lock();
        var user = members.lockUserByOpenid(identity.openid());
        MemberStore.MemberState previous = null;
        if (user.isPresent()) {
            if ("DISABLED".equals(user.get().status())) {
                throw new BusinessException(ErrorCode.USER_DISABLED);
            }
            if (!"ACTIVE".equals(user.get().status())) {
                throw new BusinessException(ErrorCode.USER_UNAVAILABLE);
            }
            previous = locked.members().stream()
                    .filter(m -> m.userId() == user.get().id())
                    .findFirst()
                    .orElse(null);
            if (previous != null && "ACTIVE".equals(previous.status())) {
                throw new BusinessException(ErrorCode.ALREADY_MEMBER);
            }
            if (previous != null && !Set.of("LEFT", "REMOVED").contains(previous.status())) {
                throw new BusinessException(ErrorCode.MEMBER_STATE_CHANGED);
            }
        }
        var row = invites.lockByHash(hash)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITE_INVALID));
        Instant decisionNow = now();
        if (row.ledgerId() != 1) {
            throw new BusinessException(ErrorCode.INVITE_INVALID);
        }
        switch (effective(row, locked, decisionNow)) {
            case "USED" -> throw new BusinessException(ErrorCode.INVITE_USED);
            case "REVOKED" -> throw new BusinessException(ErrorCode.INVITE_REVOKED);
            case "EXPIRED" -> throw new BusinessException(ErrorCode.INVITE_EXPIRED);
            case "ACTIVE" -> {
            }
            default -> throw new BusinessException(ErrorCode.INVITE_INVALID);
        }
        long active = locked.members().stream()
                .filter(m -> "ACTIVE".equals(m.status()) && "ACTIVE".equals(m.userStatus()))
                .count();
        if (active >= locked.maxMembers()) {
            throw new BusinessException(ErrorCode.MEMBER_LIMIT_REACHED);
        }
        requireLive(session);
        long userId = user.isPresent()
                ? user.get().id()
                : auth.insertUser(identity.openid(), identity.unionid(), "微信用户", decisionNow);
        InviteService.requireSafeId(userId);
        long memberId;
        if (previous == null) {
            memberId = members.insertMember(userId, decisionNow);
        } else {
            memberId = previous.memberId();
            changed(members.reactivateMember(memberId, userId, previous.status(), decisionNow));
        }
        InviteService.requireSafeId(memberId);
        auth.revokeAllSessions(userId, decisionNow);
        requireLive(session);
        auth.insertSession(userId, session.tokenHash(), session.expiresAt(), decisionNow);
        auth.updateLastLogin(userId, decisionNow);
        changed(invites.use(row.id(), userId, decisionNow));
        append(userId, "INVITE_ACCEPT", row.id(), requestId,
                Map.of("memberId", memberId, "role", "MEMBER", "activeCount", active + 1));
        return AuthResult.authenticated(session);
    }

    /** Called by member mutations after acquiring the shared guard; joins that transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> revokeCreatedBy(long userId, CurrentUser actor, String reason, String requestId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Invitation revocation requires the guarded write transaction");
        }
        if (!Set.of("ROLE_DEMOTION", "MEMBER_LEAVE", "MEMBER_REMOVE", "OWNERSHIP_TRANSFER").contains(reason)) {
            throw new IllegalArgumentException("Unsupported invitation revocation reason");
        }
        long memberId = members.lockMembers().stream()
                .filter(member -> member.userId() == userId)
                .mapToLong(MemberStore.MemberState::memberId)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_STATE_CHANGED));
        var rows = invites.lockCreatedBy(userId);
        Instant decisionNow = now(); // do not trust a time captured before waiting for invite locks
        List<Long> ids = new ArrayList<>();
        for (var row : rows) {
            if (row.ledgerId() == 1 && "ACTIVE".equals(row.status()) && row.expiresAt().isAfter(decisionNow)) {
                changed(invites.revoke(row.id(), decisionNow));
                ids.add(row.id());
            }
        }
        for (long id : ids) {
            append(actor.userId(), "INVITE_REVOKE", id, requestId,
                    Map.of("reason", reason, "createdBy", userId, "memberId", memberId));
        }
        return List.copyOf(ids);
    }

    private String effective(InviteRow row, LedgerWriteGuard.LockedLedger locked, Instant now) {
        boolean manager = locked.members().stream().anyMatch(m -> m.userId() == row.createdBy()
                && "ACTIVE".equals(m.userStatus()) && "ACTIVE".equals(m.status())
                && (m.role() == MemberRole.OWNER || m.role() == MemberRole.ADMIN));
        return InviteStatus.effective(row.status(), row.expiresAt(), manager, now);
    }

    private void requireLive(IssuedSessionToken session) {
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
    }

    private static void requireManager(MemberStore.MemberState member) {
        if (member.role() != MemberRole.OWNER && member.role() != MemberRole.ADMIN) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void append(long actorId, String action, long id, String requestId, Map<String, ?> details) {
        audit.append(new AuditLogService.AuditEvent(1L, actorId, action, "LEDGER_INVITE", id, requestId, details));
    }
}
