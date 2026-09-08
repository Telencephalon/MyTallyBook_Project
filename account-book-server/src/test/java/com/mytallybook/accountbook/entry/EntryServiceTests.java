package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.entry.store.EntryStore;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntryServiceTests {
    private static final String UUID_A = "11111111-2222-4333-8444-555555555555";

    @Test
    void auditFailureEscapesTheTransactionAndRequestsRollback() {
        class RecordingTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
            boolean rolledBack;
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rolledBack = true; }
        }
        var transactions = new RecordingTransactions();
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong())).thenReturn(40L);
        when(h.store.find(40)).thenReturn(Optional.of(row(40, "3.40", 1, UUID_A, null, 0)));
        doThrow(new IllegalStateException("audit unavailable")).when(h.audit).append(any());

        var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> template.executeWithoutResult(ignored ->
                h.service.create(h.actor, request("3.40", UUID_A), "req")));
        assertTrue(transactions.rolledBack);
    }

    @Test
    void firstWriteWinsAndReplayReturnsCurrentStateWithoutSecondAudit() {
        var h = harness(MemberRole.OWNER);
        var original = row(40, "3.40", 1, UUID_A, null, 0);
        var editedCurrent = row(40, "8.88", 1, UUID_A, null, 4);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty(), Optional.of(editedCurrent));
        when(h.store.insert(any(), any(), anyLong(), anyLong(), any(), any(), eq(UUID_A), anyLong()))
                .thenReturn(40L);
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.find(40)).thenReturn(Optional.of(original));

        var first = h.service.create(h.actor, request("3.40", UUID_A), "req-a");
        var replay = h.service.create(h.actor, request("9.99", UUID_A.toUpperCase()), "req-b");

        assertEquals(first.id(), replay.id());
        assertEquals("8.88", replay.amount());
        assertEquals(4, replay.version());
        verify(h.store, times(1)).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
        verify(h.audit, times(1)).append(any());
    }

    @Test
    void anotherActorCannotClaimAnExistingIdempotencyKey() {
        var h = harness(MemberRole.MEMBER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.of(row(40, "3.40", 99, UUID_A, null, 0)));

        assertCode(ErrorCode.ENTRY_IDEMPOTENCY_CONFLICT,
                () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
        verifyNoInteractions(h.audit);
    }

    @Test
    void deletedIdempotencyKeyNeverResurrects() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.of(
                row(40, "3.40", 1, UUID_A, Instant.parse("2026-09-01T00:00:00Z"), 1)));

        assertCode(ErrorCode.ENTRY_IDEMPOTENCY_DELETED,
                () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
        verifyNoInteractions(h.audit);
    }

    @Test
    void staleUpdateAndDeleteDoNotAudit() {
        var h = harness(MemberRole.ADMIN);
        var current = row(40, "3.40", 2, UUID_A, null, 3);
        when(h.store.find(40)).thenReturn(Optional.of(current));
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(2L))).thenReturn(0);
        when(h.store.softDelete(eq(40L), any(), anyLong(), eq(2L))).thenReturn(0);

        assertCode(ErrorCode.ENTRY_VERSION_CONFLICT, () -> h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", "晚餐", 2L), "req-u"));
        assertCode(ErrorCode.ENTRY_VERSION_CONFLICT, () -> h.service.delete(h.actor, 40, 2L, "req-d"));
        verifyNoInteractions(h.audit);
    }

    @Test
    void memberMayMutateOwnEntryButNotAnotherMembersEntry() {
        var h = harness(MemberRole.MEMBER);
        var own = row(40, "3.40", 1, UUID_A, null, 0);
        var other = row(41, "4.00", 99, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", null, 0);
        when(h.store.find(40)).thenReturn(Optional.of(own), Optional.of(row(40, "5.00", 1, UUID_A, null, 1)));
        when(h.store.find(41)).thenReturn(Optional.of(other));
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(0L))).thenReturn(1);

        assertEquals("5.00", h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", null, 0L), "req").amount());
        assertCode(ErrorCode.ACCESS_DENIED, () -> h.service.delete(h.actor, 41, 0L, "req"));
        verify(h.store, never()).softDelete(eq(41L), any(), anyLong(), anyLong());
    }

    @Test
    void latestRemovedMemberDenialHappensBeforeEntryLookupAndAudit() {
        var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(EntryStore.class);
        var audit = mock(AuditLogService.class);
        var actor = actor(1, MemberRole.MEMBER);
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(actor)).thenThrow(new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        var service = new EntryService(mock(LedgerReadGuard.class), write, store, audit);

        assertCode(ErrorCode.AUTHENTICATION_REQUIRED,
                () -> service.delete(actor, 40, 0L, "req"));
        verifyNoInteractions(store, audit);
    }

    @Test
    void disabledOriginalReferencesMayRemainButChangedDisabledReferencesAreDenied() {
        var h = harness(MemberRole.OWNER);
        var current = row(40, "3.40", 1, UUID_A, null, 0);
        when(h.store.find(40)).thenReturn(Optional.of(current), Optional.of(row(40, "5.00", 1, UUID_A, null, 1)));
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "旧分类", "DISABLED")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "旧账户", "DISABLED")));
        when(h.store.findCategory(9)).thenReturn(Optional.of(new EntryStore.CategoryReference(9, "EXPENSE", "停用分类", "DISABLED")));
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(0L))).thenReturn(1);

        assertEquals(1, h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", null, 0L), "req").version());
        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 9, 8, "2026-09-06", null, 0L), "req"));
    }

    @Test
    void categoryDirectionMustMatchAndNewReferencesMustBeActive() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "INCOME", "工资", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));

        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "1.234", "1e2", "10000000000000.00"})
    void createRejectsInexactOrOutOfRangeMoneyWithoutMutation(String amount) {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());

        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> h.service.create(h.actor, request(amount, UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong());
    }

    @Test
    void createRejectsInvalidDateAndNoteBeyondFiveHundredCodePoints() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        var invalidDate = new EntryModels.EntryInput("EXPENSE", "1.00", 7, 8, "2026-02-29", null, UUID_A);
        var longNote = new EntryModels.EntryInput("EXPENSE", "1.00", 7, 8, "2026-09-06", "😀".repeat(501), UUID_A);

        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.create(h.actor, invalidDate, "req"));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.create(h.actor, longNote, "req"));
    }

    @Test
    void readsUseSnapshotGuardAndExposeRoleDerivedControls() {
        var read = mock(LedgerReadGuard.class);
        var write = mock(LedgerWriteGuard.class);
        var store = mock(EntryStore.class);
        var actor = actor(1, MemberRole.MEMBER);
        when(read.requireActor(actor)).thenReturn(state(1, MemberRole.MEMBER));
        when(store.find(40)).thenReturn(Optional.of(row(40, "3.40", 99, UUID_A, null, 0)));
        var service = new EntryService(read, write, store, mock(AuditLogService.class));

        var view = service.get(actor, 40);

        assertFalse(view.canEdit());
        assertFalse(view.canDelete());
        verify(read).requireActor(actor);
    }

    private static Harness harness(MemberRole role) {
        var read = mock(LedgerReadGuard.class);
        var write = mock(LedgerWriteGuard.class);
        var locked = mock(LedgerWriteGuard.LockedLedger.class);
        var store = mock(EntryStore.class);
        var audit = mock(AuditLogService.class);
        var actor = actor(1, role);
        when(write.lock()).thenReturn(locked);
        when(locked.requireActor(actor)).thenReturn(state(1, role));
        return new Harness(actor, store, audit, new EntryService(read, write, store, audit));
    }

    private static EntryModels.EntryInput request(String amount, String uuid) {
        return new EntryModels.EntryInput("EXPENSE", amount, 7, 8, "2026-09-06", "晚餐", uuid);
    }

    private static EntryStore.EntryRow row(long id, String amount, long createdBy, String uuid,
                                           Instant deletedAt, long version) {
        return new EntryStore.EntryRow(id, "EXPENSE", new BigDecimal(amount), 7, "餐饮", "ACTIVE",
                8, "现金", "ACTIVE", LocalDate.of(2026, 9, 6), "晚餐",
                createdBy, "成员" + createdBy, Instant.parse("2026-09-06T01:00:00Z"),
                Instant.parse("2026-09-06T01:00:00Z"), deletedAt, uuid, version);
    }

    private static CurrentUser actor(long userId, MemberRole role) {
        return new CurrentUser(userId, 1, 12, role);
    }

    private static MemberStore.MemberState state(long userId, MemberRole role) {
        return new MemberStore.MemberState(12, userId, "ACTIVE", role, "ACTIVE", "昵称", null, Instant.EPOCH);
    }

    private static void assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable executable) {
        assertEquals(code, assertThrows(BusinessException.class, executable).errorCode());
    }

    private record Harness(CurrentUser actor, EntryStore store, AuditLogService audit, EntryService service) {}
}
