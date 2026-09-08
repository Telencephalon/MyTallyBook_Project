package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.member.store.MemberStore.LedgerState;
import com.mytallybook.accountbook.member.store.MemberStore.MemberState;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

@Component
public class LedgerWriteGuard {
    private final AuthStore authStore;
    private final MemberStore memberStore;

    public LedgerWriteGuard(AuthStore authStore, MemberStore memberStore) {
        this.authStore = authStore;
        this.memberStore = memberStore;
    }

    public LockedLedger lock() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ledger writes require an active transaction");
        }
        AuthStore.AppConfigState config;
        try {
            config = authStore.lockAppConfig();
        } catch (EmptyResultDataAccessException missingConfig) {
            throw conflict();
        }
        if (!config.initialized()) {
            throw new BusinessException(ErrorCode.SYSTEM_NOT_INITIALIZED);
        }
        if (config.maxUsers() < 1 || config.maxUsers() > 10) {
            throw conflict();
        }
        var ledger = memberStore.lockLedger().orElseThrow(LedgerWriteGuard::conflict);
        if (ledger.id() != 1 || !"ACTIVE".equals(ledger.status())
                || ledger.maxMembers() < 1 || ledger.maxMembers() > 10) {
            throw conflict();
        }
        var members = memberStore.lockMembers();
        assertOwnerInvariant(ledger, members);
        return new LockedLedger(config, ledger, members);
    }

    public void assertOwnerInvariant(LedgerState ledger, List<MemberState> members) {
        var owners = members.stream().filter(LedgerWriteGuard::active)
                .filter(member -> member.role() == MemberRole.OWNER).toList();
        if (owners.size() != 1 || owners.getFirst().userId() != ledger.ownerUserId()) {
            throw conflict();
        }
    }

    private static boolean active(MemberState member) {
        return "ACTIVE".equals(member.userStatus()) && "ACTIVE".equals(member.status());
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.LEDGER_STATE_CONFLICT);
    }

    public record LockedLedger(AuthStore.AppConfigState config, LedgerState ledger, List<MemberState> members) {
        public LockedLedger {
            members = List.copyOf(members);
        }

        public int maxMembers() {
            return Math.min(10, Math.min(config.maxUsers(), ledger.maxMembers()));
        }

        public MemberState requireActor(CurrentUser currentUser) {
            if (currentUser == null || currentUser.ledgerId() != ledger.id()) {
                throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
            return members.stream().filter(LedgerWriteGuard::active)
                    .filter(member -> member.userId() == currentUser.userId()
                            && member.memberId() == currentUser.memberId() && member.role() != null)
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        }
    }
}
