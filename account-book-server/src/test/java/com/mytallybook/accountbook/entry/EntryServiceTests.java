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
import org.junit.jupiter.params.provider.EnumSource;
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
    void personNameIsTrimmedSavedSeparatelyAndCanBeCleared() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "人情", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "微信", "ACTIVE")));
        when(h.store.insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(40L);
        when(h.store.find(40)).thenReturn(Optional.of(row(40, "500.00", 1, UUID_A, null, 0)));
        when(h.store.update(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), anyLong(), any())).thenReturn(1);

        h.service.create(h.actor, new EntryModels.EntryInput("EXPENSE", "500", 7, 8,
                "2026-09-11", "婚礼", UUID_A, "  张三  "), "req");
        verify(h.store).insert("EXPENSE", new BigDecimal("500.00"), 7, 8,
                LocalDate.of(2026, 9, 11), "婚礼", UUID_A, 1, "张三");

        h.service.update(h.actor, 40, new EntryModels.EntryUpdate("EXPENSE", "500", 7, 8,
                "2026-09-11", "婚礼", 0L, "  ", true), "req");
        verify(h.store).update(eq(40L), eq("EXPENSE"), eq(new BigDecimal("500.00")), eq(7L), eq(8L),
                eq(LocalDate.of(2026, 9, 11)), eq("婚礼"), eq(1L), any(), eq(0L), isNull());
    }

    @Test
    void personNameLengthCountsUnicodeCharactersAndRejectsOverflowBeforeWriting() {
        assertEquals("😀".repeat(64), com.mytallybook.accountbook.common.validation.BookkeepingValidation.personName("😀".repeat(64)));
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.create(h.actor,
                new EntryModels.EntryInput("EXPENSE", "500", 7, 8, "2026-09-11", null, UUID_A, "名".repeat(65)), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
    }

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
        when(h.store.insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(40L);
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
        when(h.store.insert(any(), any(), anyLong(), anyLong(), any(), any(), eq(UUID_A), anyLong(), any()))
                .thenReturn(40L);
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.find(40)).thenReturn(Optional.of(original));

        var first = h.service.create(h.actor, request("3.40", UUID_A), "req-a");
        var replay = h.service.create(h.actor, request("9.99", UUID_A.toUpperCase()), "req-b");

        assertEquals(first.id(), replay.id());
        assertEquals("8.88", replay.amount());
        assertEquals(4, replay.version());
        verify(h.store, times(1)).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
        verify(h.audit, times(1)).append(any());
    }

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    void anotherActorCannotClaimExistingKeyOrDiscoverItsDeletionState(MemberRole role) {
        var h = harness(role);
        for (boolean deleted : new boolean[]{false, true}) {
            when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.of(row(40, "3.40", 99, UUID_A,
                    deleted ? Instant.parse("2026-09-01T00:00:00Z") : null, 0)));
            assertCode(ErrorCode.ENTRY_IDEMPOTENCY_CONFLICT,
                    () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
            verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
            verifyNoInteractions(h.audit);
        }
    }

    @Test
    void deletedIdempotencyKeyNeverResurrects() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.of(
                row(40, "3.40", 1, UUID_A, Instant.parse("2026-09-01T00:00:00Z"), 1)));

        assertCode(ErrorCode.ENTRY_IDEMPOTENCY_DELETED,
                () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(h.audit);
    }

    @Test
    void staleUpdateAndDeleteDoNotAudit() {
        var h = harness(MemberRole.ADMIN);
        var current = row(40, "3.40", 1, UUID_A, null, 3);
        when(h.store.find(40)).thenReturn(Optional.of(current));
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(2L), any())).thenReturn(0);
        when(h.store.softDelete(eq(40L), any(), anyLong(), eq(2L))).thenReturn(0);

        assertCode(ErrorCode.ENTRY_VERSION_CONFLICT, () -> h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", "晚餐", 2L, null, true), "req-u"));
        assertCode(ErrorCode.ENTRY_VERSION_CONFLICT, () -> h.service.delete(h.actor, 40, 2L, "req-d"));
        verifyNoInteractions(h.audit);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void nonOwnerMayMutateOwnEntryButNotAnotherMembersEntry(MemberRole role) {
        var h = harness(role);
        var own = row(40, "3.40", 1, UUID_A, null, 0);
        var other = row(41, "4.00", 99, "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", null, 0);
        when(h.store.find(40)).thenReturn(Optional.of(own), Optional.of(row(40, "5.00", 1, UUID_A, null, 1)));
        when(h.store.find(41)).thenReturn(Optional.of(other));
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(0L), any())).thenReturn(1);

        assertEquals("5.00", h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", null, 0L, null, true), "req").amount());
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
        when(h.store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(0L), any())).thenReturn(1);

        assertEquals(1, h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", null, 0L, null, true), "req").version());
        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.update(h.actor, 40,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 9, 8, "2026-09-06", null, 0L, null, true), "req"));
    }

    @Test
    void categoryDirectionMustMatchAndNewReferencesMustBeActive() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        when(h.store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "INCOME", "工资", "ACTIVE")));
        when(h.store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));

        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> h.service.create(h.actor, request("3.40", UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "1.234", "1e2", "10000000000000.00"})
    void createRejectsInexactOrOutOfRangeMoneyWithoutMutation(String amount) {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());

        assertCode(ErrorCode.VALIDATION_FAILED,
                () -> h.service.create(h.actor, request(amount, UUID_A), "req"));
        verify(h.store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void createRejectsInvalidDateAndNoteBeyondFiveHundredCodePoints() {
        var h = harness(MemberRole.OWNER);
        when(h.store.findByClientRequestId(UUID_A)).thenReturn(Optional.empty());
        var invalidDate = new EntryModels.EntryInput("EXPENSE", "1.00", 7, 8, "2026-02-29", null, UUID_A, null);
        var longNote = new EntryModels.EntryInput("EXPENSE", "1.00", 7, 8, "2026-09-06", "😀".repeat(501), UUID_A, null);

        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.create(h.actor, invalidDate, "req"));
        assertCode(ErrorCode.VALIDATION_FAILED, () -> h.service.create(h.actor, longNote, "req"));
    }

    @Test
    void readsOutsidePersonalScopeReturnNotFound() {
        var read = mock(LedgerReadGuard.class);
        var write = mock(LedgerWriteGuard.class);
        var store = mock(EntryStore.class);
        var actor = actor(1, MemberRole.MEMBER);
        when(read.requireActor(actor)).thenReturn(state(1, MemberRole.MEMBER));
        when(store.find(40)).thenReturn(Optional.of(row(40, "3.40", 99, UUID_A, null, 0)));
        var service = new EntryService(read, write, store, mock(AuditLogService.class));

        assertCode(ErrorCode.RESOURCE_NOT_FOUND, () -> service.get(actor, 40));
        verify(read).requireActor(actor);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void personalListAndCountUseLiveCreatorEvenWithStaleOwnerToken(MemberRole role) {
        var h = harness(role);
        var staleActor = actor(1, MemberRole.OWNER);
        when(h.read.requireActor(staleActor)).thenReturn(state(1, role));
        when(h.store.list(any())).thenAnswer(call -> ((EntryFilters) call.getArgument(0)).createdBy() == null
                ? List.of(row(40, "3.40", 1, UUID_A, null, 0), row(41, "9.00", 99, UUID_A, null, 0))
                : List.of(row(40, "3.40", 1, UUID_A, null, 0)));
        when(h.store.count(any())).thenAnswer(call -> ((EntryFilters) call.getArgument(0)).createdBy() == null ? 2L : 1L);
        var filters = EntryFilters.parse(null, null, null, null, null, null, null, "2", "10");

        var page = h.service.list(staleActor, filters);

        assertEquals(List.of(1L), page.items().stream().map(EntryModels.EntryView::createdBy).toList());
        assertEquals(1, page.total());
        assertEquals(2, page.page());
        verify(h.store).list(argThat(value -> Long.valueOf(1).equals(value.createdBy()) && value.offset() == 10));
        verify(h.store).count(argThat(value -> Long.valueOf(1).equals(value.createdBy())));
        clearInvocations(h.store);
        assertCode(ErrorCode.ACCESS_DENIED, () -> h.service.list(staleActor,
                EntryFilters.parse(null, null, null, null, null, "99", null, null, null)));
        verifyNoInteractions(h.store);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void liveNonOwnerCannotReadOrMutateOthersDespiteOwnerToken(MemberRole role) {
        var h = harness(role);
        var staleActor = actor(1, MemberRole.OWNER);
        when(h.read.requireActor(staleActor)).thenReturn(state(1, role));
        when(h.locked.requireActor(staleActor)).thenReturn(state(1, role));
        when(h.store.find(41)).thenReturn(Optional.of(row(41, "4.00", 99, UUID_A, null, 0)));

        assertCode(ErrorCode.RESOURCE_NOT_FOUND, () -> h.service.get(staleActor, 41));
        assertCode(ErrorCode.ACCESS_DENIED, () -> h.service.update(staleActor, 41,
                new EntryModels.EntryUpdate("EXPENSE", "5.00", 7, 8, "2026-09-06", null, 0L, null, true), "req"));
        assertCode(ErrorCode.ACCESS_DENIED, () -> h.service.delete(staleActor, 41, 0L, "req"));
        verify(h.store, never()).update(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), anyLong(), any());
        verify(h.store, never()).softDelete(anyLong(), any(), anyLong(), anyLong());
        verifyNoInteractions(h.audit);
    }

    @Test
    void liveOwnerCanSelectAllOrAnotherCreatorAndReadTheirEntry() {
        var h = harness(MemberRole.OWNER);
        var staleActor = actor(1, MemberRole.MEMBER);
        when(h.read.requireActor(staleActor)).thenReturn(state(1, MemberRole.OWNER));
        when(h.store.find(41)).thenReturn(Optional.of(row(41, "4.00", 99, UUID_A, null, 0)));

        h.service.list(staleActor, EntryFilters.parse(null, null, null, null, null, null, null, null, null));
        h.service.list(staleActor, EntryFilters.parse(null, null, null, null, null, "99", null, null, null));
        assertTrue(h.service.get(staleActor, 41).canEdit());
        verify(h.store).list(argThat(value -> value.createdBy() == null));
        verify(h.store).count(argThat(value -> value.createdBy() == null));
        verify(h.store).list(argThat(value -> Long.valueOf(99).equals(value.createdBy())));
        verify(h.store).count(argThat(value -> Long.valueOf(99).equals(value.createdBy())));
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void creatorOptionsNeverDiscloseOtherCurrentOrHistoricalCreators(MemberRole role) {
        var h = harness(role);
        when(h.store.creators(any())).thenAnswer(call -> call.getArgument(0) == null
                ? List.of(new EntryStore.CreatorRow(1, "昵称"), new EntryStore.CreatorRow(99, "历史成员"))
                : List.of(new EntryStore.CreatorRow(1, "昵称")));

        assertEquals(List.of(1L), h.service.creators(h.actor).items().stream()
                .map(EntryModels.CreatorOption::userId).toList());
        verify(h.store).creators(1L);
    }

    @Test
    void ownerCreatorOptionsRetainFormerCreators() {
        var h = harness(MemberRole.OWNER);
        when(h.store.creators(isNull())).thenReturn(List.of(new EntryStore.CreatorRow(1, "昵称"),
                new EntryStore.CreatorRow(99, "历史成员")));

        assertEquals(List.of(1L, 99L), h.service.creators(h.actor).items().stream()
                .map(EntryModels.CreatorOption::userId).toList());
        verify(h.store).creators(isNull());
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
        when(read.requireActor(actor)).thenReturn(state(1, role));
        return new Harness(actor, store, audit, new EntryService(read, write, store, audit), read, locked);
    }

    private static EntryModels.EntryInput request(String amount, String uuid) {
        return new EntryModels.EntryInput("EXPENSE", amount, 7, 8, "2026-09-06", "晚餐", uuid, null);
    }

    private static EntryStore.EntryRow row(long id, String amount, long createdBy, String uuid,
                                           Instant deletedAt, long version) {
        return new EntryStore.EntryRow(id, "EXPENSE", new BigDecimal(amount), 7, "餐饮", "ACTIVE",
                8, "现金", "ACTIVE", LocalDate.of(2026, 9, 6), "晚餐",
                createdBy, "成员" + createdBy, Instant.parse("2026-09-06T01:00:00Z"),
                Instant.parse("2026-09-06T01:00:00Z"), deletedAt, uuid, version, null);
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

    private record Harness(CurrentUser actor, EntryStore store, AuditLogService audit, EntryService service,
                           LedgerReadGuard read, LedgerWriteGuard.LockedLedger locked) {}
}
