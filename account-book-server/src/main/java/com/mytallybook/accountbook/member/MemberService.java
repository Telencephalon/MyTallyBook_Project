package com.mytallybook.accountbook.member;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.invite.InviteService;
import com.mytallybook.accountbook.invite.InviteTransactionService;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.member.store.MemberStore.MemberState;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;

@Service
public class MemberService {
    private final LedgerWriteGuard guard;
    private final AuthStore auth;
    private final MemberStore members;
    private final InviteTransactionService invites;
    private final AuditLogService audit;
    private final Clock clock;

    public MemberService(LedgerWriteGuard guard, AuthStore auth, MemberStore members,
                         InviteTransactionService invites, AuditLogService audit, Clock clock) {
        this.guard = guard;
        this.auth = auth;
        this.members = members;
        this.invites = invites;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MemberList list(CurrentUser actor) {
        AuthStore.AppConfigState config;
        try {
            config = auth.readAppConfig();
        } catch (EmptyResultDataAccessException missing) {
            throw error(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        var ledger = members.readLedger().orElseThrow(() -> error(ErrorCode.LEDGER_STATE_CONFLICT));
        if (!config.initialized() || config.maxUsers() < 1 || config.maxUsers() > 10
                || ledger.id() != 1 || !"ACTIVE".equals(ledger.status())
                || ledger.maxMembers() < 1 || ledger.maxMembers() > 10) {
            throw error(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        var rows = members.readMembers();
        guard.assertOwnerInvariant(ledger, rows);
        var snapshot = new LedgerWriteGuard.LockedLedger(config, ledger, rows);
        snapshot.requireActor(actor);
        var items = rows.stream().filter(MemberService::active)
                .sorted(Comparator.comparing(MemberState::joinedAt).thenComparingLong(MemberState::memberId))
                .map(row -> view(row, row.role())).toList();
        return new MemberList(items, items.size(), snapshot.maxMembers(), safeId(ledger.ownerUserId()));
    }

    @Transactional
    public MemberView changeRole(CurrentUser actor, long memberId, MemberRole role, String requestId) {
        safeId(memberId);
        if (role != MemberRole.ADMIN && role != MemberRole.MEMBER) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
        var locked = guard.lock();
        var current = locked.requireActor(actor);
        requireOwner(current);
        var target = target(locked, memberId);
        if (target.userId() == current.userId() || target.role() == MemberRole.OWNER) {
            throw error(ErrorCode.ACCESS_DENIED);
        }
        var result = view(target, role);
        if (target.role() == role) {
            return result;
        }
        changed(members.changeRole(memberId, target.role(), role));
        int revoked = 0;
        if (target.role() == MemberRole.ADMIN) {
            revoked = invites.revokeCreatedBy(target.userId(), actor, "ROLE_DEMOTION", requestId).size();
        }
        append(actor, "MEMBER_ROLE_CHANGE", memberId, requestId,
                Map.of("userId", target.userId(), "oldRole", target.role().name(), "newRole", role.name(),
                        "revokedInvites", revoked));
        return result;
    }

    @Transactional
    public RemovedMember remove(CurrentUser actor, long memberId, String requestId) {
        safeId(memberId);
        var locked = guard.lock();
        var current = locked.requireActor(actor);
        var target = target(locked, memberId);
        boolean self = current.userId() == target.userId();
        if (self) {
            if (current.role() == MemberRole.OWNER) {
                throw error(ErrorCode.OWNER_TRANSFER_REQUIRED);
            }
        } else {
            if (current.role() == MemberRole.MEMBER || target.role() == MemberRole.OWNER
                    || (current.role() == MemberRole.ADMIN && target.role() == MemberRole.ADMIN)) {
                throw error(ErrorCode.ACCESS_DENIED);
            }
            if (target.role() == MemberRole.ADMIN) {
                throw error(ErrorCode.ADMIN_DEMOTION_REQUIRED);
            }
        }
        safeId(target.userId());
        String status = self ? "LEFT" : "REMOVED";
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        changed(members.remove(memberId, target.role(), status, now));
        auth.revokeAllSessions(target.userId(), now);
        int revoked = invites.revokeCreatedBy(target.userId(), actor,
                self ? "MEMBER_LEAVE" : "MEMBER_REMOVE", requestId).size();
        append(actor, self ? "MEMBER_LEAVE" : "MEMBER_REMOVE", memberId, requestId,
                Map.of("userId", target.userId(), "oldRole", target.role().name(), "revokedInvites", revoked));
        return new RemovedMember(memberId, status);
    }

    @Transactional
    public OwnershipTransfer transfer(CurrentUser actor, long memberId, String requestId) {
        safeId(memberId);
        var locked = guard.lock();
        var current = locked.requireActor(actor);
        requireOwner(current);
        var target = target(locked, memberId);
        if (target.userId() == current.userId()) {
            throw error(ErrorCode.VALIDATION_FAILED);
        }
        safeId(current.memberId());
        var result = new OwnershipTransfer(safeId(target.userId()), safeId(current.userId()));
        changed(members.changeRole(current.memberId(), MemberRole.OWNER, MemberRole.MEMBER));
        changed(members.changeRole(target.memberId(), target.role(), MemberRole.OWNER));
        changed(members.transferOwner(current.userId(), target.userId(), locked.ledger().version()));
        int revoked = invites.revokeCreatedBy(current.userId(), actor, "OWNERSHIP_TRANSFER", requestId).size();
        // Re-read the persisted current state; never validate a locally manufactured post-update snapshot.
        var updatedLedger = members.lockLedger().orElseThrow(() -> error(ErrorCode.LEDGER_STATE_CONFLICT));
        guard.assertOwnerInvariant(updatedLedger, members.lockMembers());
        if (updatedLedger.ownerUserId() != target.userId()) {
            throw error(ErrorCode.LEDGER_STATE_CONFLICT);
        }
        append(actor, "OWNERSHIP_TRANSFER", memberId, requestId,
                Map.of("ownerUserId", target.userId(), "previousOwnerUserId", current.userId(),
                        "previousOwnerMemberId", current.memberId(), "revokedInvites", revoked));
        return result;
    }

    private static MemberState target(LedgerWriteGuard.LockedLedger locked, long memberId) {
        return locked.members().stream().filter(row -> row.memberId() == memberId).filter(MemberService::active)
                .findFirst().orElseThrow(() -> error(ErrorCode.MEMBER_STATE_CHANGED));
    }

    private static boolean active(MemberState member) {
        return "ACTIVE".equals(member.status()) && "ACTIVE".equals(member.userStatus());
    }

    private static MemberView view(MemberState row, MemberRole role) {
        return new MemberView(safeId(row.memberId()), safeId(row.userId()), row.nickname(), row.displayName(), role, row.joinedAt());
    }

    private static long safeId(long id) {
        return InviteService.requireSafeId(id);
    }

    private static void requireOwner(MemberState actor) {
        if (actor.role() != MemberRole.OWNER) {
            throw error(ErrorCode.ACCESS_DENIED);
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw error(ErrorCode.MEMBER_STATE_CHANGED);
        }
    }

    private static BusinessException error(ErrorCode code) {
        return new BusinessException(code);
    }

    private void append(CurrentUser actor, String action, long memberId, String requestId, Map<String, ?> details) {
        audit.append(new AuditLogService.AuditEvent(1L, actor.userId(), action, "LEDGER_MEMBER", memberId, requestId, details));
    }
}
