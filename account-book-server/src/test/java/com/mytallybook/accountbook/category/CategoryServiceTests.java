package com.mytallybook.accountbook.category;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.category.store.CategoryStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.ledger.*;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryServiceTests {
    @Test void auditFailureEscapesRecordingTransactionBoundaryAndRequestsRollback() {
        class RecordingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
            boolean rolledBack;
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
                rolledBack = true;
            }
        }
        var transactions = new RecordingTransactions();
        var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(CategoryStore.class);
        var audit = mock(AuditLogService.class);
        var owner = actor(MemberRole.OWNER);
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(owner)).thenReturn(state(MemberRole.OWNER));
        when(store.insert(any(), any(), any(), any(), anyInt(), any())).thenReturn(7L);
        when(store.find(7)).thenReturn(Optional.of(new CategoryStore.CategoryRow(
                7, "EXPENSE", "餐饮", null, null, 0, false, "ACTIVE")));
        doThrow(new IllegalStateException("audit unavailable")).when(audit).append(any());
        var service = new CategoryService(mock(LedgerReadGuard.class), write, store, audit);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(ignored -> service.create(
                owner, new CategoryModels.CategoryInput("EXPENSE", "餐饮", null, null, 0, "ACTIVE"), "req")));
        assertTrue(transactions.rolledBack);
    }
    @Test void membersCanReadButCannotMutate() {
        var read = mock(LedgerReadGuard.class); var write = mock(LedgerWriteGuard.class);
        var store = mock(CategoryStore.class); var audit = mock(AuditLogService.class);
        var member = actor(MemberRole.MEMBER); when(read.requireActor(member)).thenReturn(state(MemberRole.MEMBER));
        var locked = mock(LedgerWriteGuard.LockedLedger.class); when(write.lock()).thenReturn(locked); when(locked.requireActor(member)).thenReturn(state(MemberRole.MEMBER));
        when(store.list(null, null)).thenReturn(List.of(new CategoryStore.CategoryRow(7,"EXPENSE","餐饮",null,"#112233",0,false,"ACTIVE")));
        var service = new CategoryService(read, write, store, audit);
        assertEquals(1, service.list(member, null, null).items().size());
        assertThrows(BusinessException.class, () -> service.create(member,
                new CategoryModels.CategoryInput("EXPENSE","餐饮",null,null,0,"ACTIVE"), "req"));
        verify(store, never()).insert(any(),any(),any(),any(),anyInt(),any()); verifyNoInteractions(audit);
    }

    @Test void adminWriteUsesLockedLatestRoleThenAudits() {
        var read = mock(LedgerReadGuard.class); var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class); var store = mock(CategoryStore.class); var audit = mock(AuditLogService.class);
        var admin = actor(MemberRole.ADMIN); when(write.lock()).thenReturn(locked); when(locked.requireActor(admin)).thenReturn(state(MemberRole.ADMIN));
        when(store.insert("EXPENSE","餐饮",null,null,0,"ACTIVE")).thenReturn(7L);
        when(store.find(7)).thenReturn(Optional.of(new CategoryStore.CategoryRow(7,"EXPENSE","餐饮",null,null,0,false,"ACTIVE")));
        var result = new CategoryService(read, write, store, audit).create(admin,
                new CategoryModels.CategoryInput("EXPENSE"," 餐饮 ",null,null,null,null), "req-7");
        assertEquals(7, result.id()); verify(audit).append(any(AuditLogService.AuditEvent.class));
    }

    @Test void referencedDeleteDoesNotMutateOrAudit() {
        var write = mock(LedgerWriteGuard.class); var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(CategoryStore.class); var audit = mock(AuditLogService.class); var owner = actor(MemberRole.OWNER);
        when(write.lock()).thenReturn(locked); when(locked.requireActor(owner)).thenReturn(state(MemberRole.OWNER));
        when(store.find(7)).thenReturn(Optional.of(new CategoryStore.CategoryRow(7,"EXPENSE","餐饮",null,null,0,false,"ACTIVE")));
        when(store.referenceCount(7)).thenReturn(1L);
        assertThrows(BusinessException.class, () -> new CategoryService(mock(LedgerReadGuard.class), write, store, audit).delete(owner,7,"req"));
        verify(store, never()).delete(anyLong()); verifyNoInteractions(audit);
    }

    private static CurrentUser actor(MemberRole role) { return new CurrentUser(2, 1, 12, role); }
    private static MemberStore.MemberState state(MemberRole role) {
        return new MemberStore.MemberState(12, 2, "ACTIVE", role, "ACTIVE", "n", null,
                java.time.Instant.EPOCH);
    }
}
