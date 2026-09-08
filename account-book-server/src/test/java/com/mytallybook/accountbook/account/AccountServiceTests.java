package com.mytallybook.accountbook.account;

import com.mytallybook.accountbook.account.store.AccountStore;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.ledger.*;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTests {
    @Test void currentBalanceIsReturnedExactlyAndMembersCannotCreate() {
        var read = mock(LedgerReadGuard.class);
        var write = mock(LedgerWriteGuard.class);
        var store = mock(AccountStore.class);
        var audit = mock(AuditLogService.class);
        var member = actor(MemberRole.MEMBER);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        when(read.requireActor(member)).thenReturn(state(MemberRole.MEMBER));
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(member)).thenReturn(state(MemberRole.MEMBER));
        when(store.list(null)).thenReturn(List.of(new AccountStore.AccountRow(
                7, "现金", "CASH", new BigDecimal("0.10"), new BigDecimal("0.30"), 0, "ACTIVE", 2)));
        var service = new AccountService(read, write, store, audit);
        assertEquals("0.30", service.list(member, null).items().getFirst().currentBalance());
        assertThrows(BusinessException.class, () -> service.create(member,
                new AccountModels.AccountInput("现金", "CASH", "-1.25", 0, "ACTIVE"), "req"));
        verify(store, never()).insert(any(), any(), any(), anyInt(), any());
        verifyNoInteractions(audit);
    }

    @Test void staleUpdateDoesNotAudit() {
        var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(AccountStore.class);
        var audit = mock(AuditLogService.class);
        var owner = actor(MemberRole.OWNER);
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(owner)).thenReturn(state(MemberRole.OWNER));
        when(store.find(7)).thenReturn(Optional.of(new AccountStore.AccountRow(
                7, "现金", "CASH", BigDecimal.ZERO, BigDecimal.ZERO, 0, "ACTIVE", 2)));
        when(store.update(7, "现金", 0, "ACTIVE", 1)).thenReturn(0);
        assertThrows(BusinessException.class, () -> new AccountService(
                mock(LedgerReadGuard.class), write, store, audit)
                .update(owner, 7, new AccountModels.AccountUpdate("现金", 0, "ACTIVE", 1L), "req"));
        verifyNoInteractions(audit);
    }

    @Test void referencedDeleteIncludesHistoryAndDoesNotMutate() {
        var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(AccountStore.class);
        var audit = mock(AuditLogService.class);
        var owner = actor(MemberRole.OWNER);
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(owner)).thenReturn(state(MemberRole.OWNER));
        when(store.find(7)).thenReturn(Optional.of(new AccountStore.AccountRow(
                7, "现金", "CASH", BigDecimal.ZERO, BigDecimal.ZERO, 0, "ACTIVE", 2)));
        when(store.referenceCount(7)).thenReturn(1L);
        assertThrows(BusinessException.class, () -> new AccountService(
                mock(LedgerReadGuard.class), write, store, audit).delete(owner, 7, 2L, "req"));
        verify(store, never()).delete(anyLong(), anyLong());
        verifyNoInteractions(audit);
    }

    private static CurrentUser actor(MemberRole role) {
        return new CurrentUser(2, 1, 12, role);
    }

    private static MemberStore.MemberState state(MemberRole role) {
        return new MemberStore.MemberState(12, 2, "ACTIVE", role, "ACTIVE", "n", null, Instant.EPOCH);
    }
}
