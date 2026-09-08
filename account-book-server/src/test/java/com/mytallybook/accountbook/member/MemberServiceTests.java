package com.mytallybook.accountbook.member;

import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.*;
import com.mytallybook.accountbook.invite.InviteTransactionService;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.ledger.LedgerWriteGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import com.mytallybook.accountbook.support.TestTransactions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemberServiceTests {
    static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    final AuthStore auth = mock(AuthStore.class);
    final MemberStore members = mock(MemberStore.class);
    final InviteStore inviteStore = mock(InviteStore.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final List<MemberStore.MemberState> rows = new ArrayList<>();
    final List<AuditLogService.AuditEvent> events = new ArrayList<>();
    final Map<Long, InviteStore.InviteRow> invitationRows = new LinkedHashMap<>();
    final Set<String> liveSessions = new HashSet<>(Set.of("member-old-a", "member-old-b", "admin-old"));
    final List<String> operations = new ArrayList<>();
    MemberStore.LedgerState ledger = new MemberStore.LedgerState(1, 1, 9, "ACTIVE", 4);
    final CurrentUser owner = principal(1, MemberRole.OWNER);
    final CurrentUser member = principal(2, MemberRole.MEMBER);
    final CurrentUser admin = principal(3, MemberRole.ADMIN);
    final MemberService service;

    MemberServiceTests() {
        var guard = new LedgerWriteGuard(auth, members);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var invites = new InviteTransactionService(guard, auth, members, inviteStore, audit, clock);
        var factory = new ProxyFactory(new MemberService(guard, auth, members, invites, audit, clock));
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(TestTransactions.template().getTransactionManager());
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        service = (MemberService) factory.getProxy();
    }

    @BeforeEach void setup() {
        rows.add(row(1, MemberRole.OWNER));
        rows.add(row(2, MemberRole.MEMBER));
        rows.add(row(3, MemberRole.ADMIN));
        when(auth.lockAppConfig()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            operations.add("config-lock");
            return new AuthStore.AppConfigState(true, 8, 1);
        });
        when(auth.readAppConfig()).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isEqualTo(4);
            return new AuthStore.AppConfigState(true, 8, 1);
        });
        when(members.lockLedger()).thenAnswer(call -> {
            operations.add("ledger-lock");
            return Optional.of(ledger);
        });
        when(members.readLedger()).thenAnswer(call -> Optional.of(ledger));
        when(members.readMembers()).thenAnswer(call -> List.copyOf(rows));
        when(members.lockMembers()).thenAnswer(call -> {
            operations.add("members-lock");
            return List.copyOf(rows);
        });
        when(members.changeRole(anyLong(), any(), any())).thenAnswer(call -> {
            long id = call.getArgument(0);
            MemberRole expected = call.getArgument(1), replacement = call.getArgument(2);
            var old = rows.stream().filter(row -> row.memberId() == id && row.role() == expected && row.status().equals("ACTIVE")).findFirst();
            if (old.isEmpty()) return 0;
            operations.add("role-" + id + "-" + replacement);
            replace(old.get(), replacement, "ACTIVE");
            return 1;
        });
        when(members.remove(anyLong(), any(), anyString(), any())).thenAnswer(call -> {
            long id = call.getArgument(0);
            MemberRole expected = call.getArgument(1);
            var old = rows.stream().filter(row -> row.memberId() == id && row.role() == expected && row.status().equals("ACTIVE")).findFirst();
            if (old.isEmpty()) return 0;
            operations.add("remove-" + id);
            replace(old.get(), expected, call.getArgument(2));
            return 1;
        });
        when(members.transferOwner(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            long expectedOwner = call.getArgument(0), next = call.getArgument(1), version = call.getArgument(2);
            if (ledger.ownerUserId() != expectedOwner || ledger.version() != version) return 0;
            operations.add("transfer");
            ledger = new MemberStore.LedgerState(1, next, 9, "ACTIVE", version + 1);
            return 1;
        });
        when(inviteStore.lockCreatedBy(anyLong())).thenAnswer(call -> {
            long userId = call.getArgument(0);
            operations.add("invites-lock");
            return invitationRows.values().stream().filter(row -> row.createdBy() == userId).toList();
        });
        when(inviteStore.revoke(anyLong(), any())).thenAnswer(call -> {
            long id = call.getArgument(0);
            var old = invitationRows.get(id);
            if (old == null || !old.status().equals("ACTIVE")) return 0;
            invitationRows.put(id, new InviteStore.InviteRow(id, 1, old.createdBy(), old.createdAt(), old.expiresAt(), "REVOKED", null, null));
            operations.add("invite-revoke");
            return 1;
        });
        doAnswer(call -> {
            long id = call.getArgument(0);
            liveSessions.removeIf(token -> token.startsWith(id == 2 ? "member-" : "admin-"));
            operations.add("sessions-revoke");
            return null;
        }).when(auth).revokeAllSessions(anyLong(), any());
        doAnswer(call -> {
            events.add(call.getArgument(0));
            operations.add("audit-" + events.getLast().action());
            return null;
        }).when(audit).append(any());
    }

    @Test void listUsesRepeatableReadSnapshotFiltersBothStatusesAndSortsByJoinThenId() {
        Collections.reverse(rows);
        rows.add(new MemberStore.MemberState(14, 4, "DISABLED", MemberRole.MEMBER, "ACTIVE", "Hidden", null, NOW));
        rows.add(new MemberStore.MemberState(15, 5, "ACTIVE", MemberRole.MEMBER, "LEFT", "Hidden", null, NOW));
        rows.add(new MemberStore.MemberState(16, 6, "ACTIVE", MemberRole.MEMBER, "ACTIVE", "Older", null, Instant.EPOCH.minusSeconds(1)));
        var result = service.list(member);
        assertThat(result.items()).extracting(MemberView::memberId).containsExactly(16L, 11L, 12L, 13L);
        assertThat(result.activeCount()).isEqualTo(4);
        assertThat(result.maxMembers()).isEqualTo(8);
        assertThat(result.ownerUserId()).isEqualTo(1);
        assertThat(result.items().get(2).nickname()).isEqualTo("Name2");
        assertThat(result.items().get(2).displayName()).isEqualTo("Alias2");
        assertThat(operations).isEmpty();
        verifyNoInteractions(inviteStore, audit);
    }

    @Test void staleOwnerCannotTransferAfterDemotionAndCannotPerformAnyMutation() {
        replace(rows.get(0), MemberRole.MEMBER, "ACTIVE");
        replace(find(3), MemberRole.OWNER, "ACTIVE");
        ledger = new MemberStore.LedgerState(1, 3, 9, "ACTIVE", 5);
        fails(ErrorCode.ACCESS_DENIED, () -> service.transfer(owner, 12, "r-transfer"));
        fails(ErrorCode.ACCESS_DENIED, () -> service.changeRole(owner, 12, MemberRole.ADMIN, "r-role"));
        fails(ErrorCode.ACCESS_DENIED, () -> service.remove(owner, 12, "r-remove"));
        noWrites();
    }

    @ParameterizedTest @CsvSource({"OWNER,2,ADMIN", "OWNER,3,MEMBER"})
    void onlyOwnerCanModifyOtherNonOwnerRoles(MemberRole actorRole, long targetUser, MemberRole next) {
        var result = service.changeRole(principal(1, actorRole), targetUser + 10, next, "r-role");
        assertThat(result.role()).isEqualTo(next);
        assertThat(result.displayName()).isEqualTo("Alias" + targetUser);
        assertThat(find(targetUser).role()).isEqualTo(next);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.action()).isEqualTo("MEMBER_ROLE_CHANGE");
            assertThat(event.details().keySet()).containsExactlyInAnyOrder("userId", "oldRole", "newRole", "revokedInvites");
        });
        assertThat(operations.subList(0, 3)).containsExactly("config-lock", "ledger-lock", "members-lock");
        assertThat(liveSessions).hasSize(3);
    }

    @ParameterizedTest @CsvSource({"2,MEMBER,12", "2,MEMBER,13", "3,ADMIN,12", "3,ADMIN,13", "1,OWNER,11"})
    void roleMatrixRejectsNonOwnerOrSelf(long actorId, MemberRole role, long target) {
        fails(ErrorCode.ACCESS_DENIED, () -> service.changeRole(principal(actorId, role), target, MemberRole.ADMIN, "r"));
        noWrites();
    }

    @Test void sameRoleDoesNotAuditRevokeOrUpdate() {
        assertThat(service.changeRole(owner, 13, MemberRole.ADMIN, "r").role()).isEqualTo(MemberRole.ADMIN);
        noWrites();
    }

    @Test void demotionPhysicallyRevokesOnlyUnusedUnexpiredInvitesAndPromotionCannotReviveThem() {
        invitationRows.put(51L, invite(51, 3, "ACTIVE", NOW.plusSeconds(1)));
        invitationRows.put(52L, invite(52, 3, "ACTIVE", NOW));
        invitationRows.put(53L, invite(53, 3, "USED", NOW.plusSeconds(60)));
        invitationRows.put(54L, invite(54, 3, "REVOKED", NOW.plusSeconds(60)));
        service.changeRole(owner, 13, MemberRole.MEMBER, "r");
        service.changeRole(owner, 13, MemberRole.ADMIN, "r-promote");
        assertThat(invitationRows.get(51L).status()).isEqualTo("REVOKED");
        assertThat(invitationRows.get(52L).status()).isEqualTo("ACTIVE");
        assertThat(invitationRows.get(53L).status()).isEqualTo("USED");
        assertThat(invitationRows.get(54L).status()).isEqualTo("REVOKED");
        assertThat(events).extracting(AuditLogService.AuditEvent::action)
                .containsExactly("INVITE_REVOKE", "MEMBER_ROLE_CHANGE", "MEMBER_ROLE_CHANGE");
        assertThat(events.getFirst().details().get("reason")).isEqualTo("ROLE_DEMOTION");
    }

    @ParameterizedTest @CsvSource({"1,OWNER,12,REMOVED", "3,ADMIN,12,REMOVED", "2,MEMBER,12,LEFT", "3,ADMIN,13,LEFT"})
    void removeAndLeaveMatrixRevokesEveryOldSessionButPreservesAccountAndAlias(long actorId, MemberRole role, long target, String status) {
        invitationRows.put(51L, invite(51, 3, "ACTIVE", NOW.plusSeconds(1)));
        var result = service.remove(principal(actorId, role), target, "r");
        assertThat(result).isEqualTo(new RemovedMember(target, status));
        var stored = find(target - 10);
        assertThat(stored.status()).isEqualTo(status);
        assertThat(stored.userStatus()).isEqualTo("ACTIVE");
        assertThat(stored.displayName()).isEqualTo("Alias" + (target - 10));
        assertThat(liveSessions).noneMatch(token -> token.startsWith(target == 12 ? "member-" : "admin-"));
        verify(auth).revokeAllSessions(target - 10, NOW);
        assertThat(events.getLast().action()).isEqualTo(status.equals("LEFT") ? "MEMBER_LEAVE" : "MEMBER_REMOVE");
        assertThat(operations.getLast()).startsWith("audit-");
        if (target == 13) assertThat(invitationRows.get(51L).status()).isEqualTo("REVOKED");
        verify(auth, never()).revokeSession(anyString(), any());
        verify(auth, never()).updateUserProfile(anyLong(), anyBoolean(), any(), anyBoolean(), any(), any());
        // Reusing the retained membership cannot revive already revoked old sessions.
        replace(stored, MemberRole.MEMBER, "ACTIVE");
        assertThat(liveSessions).noneMatch(token -> token.startsWith(target == 12 ? "member-" : "admin-"));
    }

    @ParameterizedTest @CsvSource({"1,OWNER,11,OWNER_TRANSFER_REQUIRED", "1,OWNER,13,ADMIN_DEMOTION_REQUIRED",
            "3,ADMIN,11,ACCESS_DENIED", "3,ADMIN,14,ACCESS_DENIED", "2,MEMBER,13,ACCESS_DENIED", "2,MEMBER,11,ACCESS_DENIED"})
    void removalDenialsPreserveRoleStatusSessionsAndInvites(long actorId, MemberRole role, long target, ErrorCode error) {
        rows.add(row(4, MemberRole.ADMIN));
        fails(error, () -> service.remove(principal(actorId, role), target, "r"));
        noWrites();
    }

    @Test void adminMustBeDemotedBeforeOwnerCanRemove() {
        fails(ErrorCode.ADMIN_DEMOTION_REQUIRED, () -> service.remove(owner, 13, "r"));
        service.changeRole(owner, 13, MemberRole.MEMBER, "r-demote");
        assertThat(service.remove(owner, 13, "r-remove").status()).isEqualTo("REMOVED");
        assertThat(find(3).role()).isEqualTo(MemberRole.MEMBER);
    }

    @ParameterizedTest @CsvSource({"1,OWNER,MEMBER_REMOVE", "2,MEMBER,MEMBER_LEAVE"})
    void everyRemovalPhysicallyClosesResidualMemberInvites(long actorId, MemberRole actorRole, String reason) {
        invitationRows.put(51L, invite(51, 2, "ACTIVE", NOW.plusSeconds(60)));
        service.remove(principal(actorId, actorRole), 12, "r-residual");
        assertThat(invitationRows.get(51L).status()).isEqualTo("REVOKED");
        assertThat(events.getFirst().action()).isEqualTo("INVITE_REVOKE");
        assertThat(events.getFirst().details().get("reason")).isEqualTo(reason);
    }

    @ParameterizedTest @ValueSource(longs = {2, 3})
    void transferChangesBothRolesOwnerVersionAndRevokesOldOwnerInvitesWithoutSessionChurn(long targetUser) {
        invitationRows.put(51L, invite(51, 1, "ACTIVE", NOW.plusSeconds(60)));
        assertThat(service.transfer(owner, targetUser + 10, "r-transfer")).isEqualTo(new OwnershipTransfer(targetUser, 1));
        assertThat(find(1).role()).isEqualTo(MemberRole.MEMBER);
        assertThat(find(targetUser).role()).isEqualTo(MemberRole.OWNER);
        assertThat(ledger.ownerUserId()).isEqualTo(targetUser);
        assertThat(ledger.version()).isEqualTo(5);
        assertThat(invitationRows.get(51L).status()).isEqualTo("REVOKED");
        assertThat(liveSessions).hasSize(3);
        assertThat(events).extracting(AuditLogService.AuditEvent::action).containsExactly("INVITE_REVOKE", "OWNERSHIP_TRANSFER");
        assertThat(operations.subList(operations.size() - 3, operations.size()))
                .containsExactly("ledger-lock", "members-lock", "audit-OWNERSHIP_TRANSFER");
        int previousEvents = events.size();
        fails(ErrorCode.ACCESS_DENIED, () -> service.transfer(owner, targetUser == 2 ? 13 : 12, "r-second"));
        assertThat(events).hasSize(previousEvents);
    }

    @Test void transferToSelfAndInactiveTargetCannotMutate() {
        fails(ErrorCode.VALIDATION_FAILED, () -> service.transfer(owner, 11, "r"));
        replace(find(2), MemberRole.MEMBER, "REMOVED");
        fails(ErrorCode.MEMBER_STATE_CHANGED, () -> service.transfer(owner, 12, "r"));
        noWrites();
    }

    @Test void transferValidatesFreshStoredOwnerSetRatherThanPredictedRoles() {
        when(members.transferOwner(anyLong(), anyLong(), anyLong())).thenReturn(1); // reports success but leaves ledger stale
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.transfer(owner, 12, "r"));
        assertThat(events).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"role", "remove", "transfer-old", "transfer-new", "transfer-ledger"})
    void failedConditionalUpdateCannotProduceSuccessAudit(String operation) {
        switch (operation) {
            case "role", "transfer-new" -> when(members.changeRole(eq(12L), any(), any())).thenReturn(0);
            case "remove" -> when(members.remove(eq(12L), any(), anyString(), any())).thenReturn(0);
            case "transfer-old" -> when(members.changeRole(eq(11L), any(), any())).thenReturn(0);
            case "transfer-ledger" -> when(members.transferOwner(anyLong(), anyLong(), anyLong())).thenReturn(0);
        }
        fails(ErrorCode.MEMBER_STATE_CHANGED, () -> operation.equals("role")
                ? service.changeRole(owner, 12, MemberRole.ADMIN, "r")
                : operation.equals("remove") ? service.remove(owner, 12, "r") : service.transfer(owner, 12, "r"));
        assertThat(events).isEmpty();
    }

    @Test void auditFailureEscapesTransactionBoundaryInsteadOfReportingSuccess() {
        doThrow(new IllegalStateException("synthetic-audit-failure")).when(audit).append(any());
        assertThatThrownBy(() -> service.remove(owner, 12, "r")).isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic-audit-failure");
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, 9007199254740992L, Long.MAX_VALUE})
    void unsafeIdsCannotReachStores(long id) {
        fails(ErrorCode.VALIDATION_FAILED, () -> service.changeRole(owner, id, MemberRole.ADMIN, "r"));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.remove(owner, id, "r"));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.transfer(owner, id, "r"));
        assertThat(operations).isEmpty();
    }

    @Test void ownerAssignmentAndMissingRoleFailBeforeLocking() {
        fails(ErrorCode.VALIDATION_FAILED, () -> service.changeRole(owner, 12, MemberRole.OWNER, "r"));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.changeRole(owner, 12, null, "r"));
        assertThat(operations).isEmpty();
    }

    @Test void missingAndDisabledTargetsCannotBeModified() {
        rows.add(new MemberStore.MemberState(14, 4, "DISABLED", MemberRole.MEMBER, "ACTIVE", "Disabled", null, NOW));
        for (long target : new long[]{14, 999}) {
            fails(ErrorCode.MEMBER_STATE_CHANGED, () -> service.changeRole(owner, target, MemberRole.ADMIN, "r"));
            fails(ErrorCode.MEMBER_STATE_CHANGED, () -> service.remove(owner, target, "r"));
            fails(ErrorCode.MEMBER_STATE_CHANGED, () -> service.transfer(owner, target, "r"));
        }
        noWrites();
    }

    @Test void invalidConfigAndOwnerInvariantFailClosedForAllMutations() {
        rows.add(row(4, MemberRole.OWNER));
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.changeRole(owner, 12, MemberRole.ADMIN, "r"));
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.remove(owner, 12, "r"));
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.transfer(owner, 12, "r"));
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.list(owner));
        noWrites();
    }

    @ParameterizedTest @ValueSource(ints = {0, 11})
    void listRejectsInvalidCapacityWithoutTakingWriteLock(int capacity) {
        doReturn(new AuthStore.AppConfigState(true, capacity, 1)).when(auth).readAppConfig();
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.list(owner));
        assertThat(operations).isEmpty();
    }

    @Test void ledgerCapacityCanBeTheEffectiveLowerLimit() {
        ledger = new MemberStore.LedgerState(1, 1, 4, "ACTIVE", 4);
        assertThat(service.list(owner).maxMembers()).isEqualTo(4);
    }

    @Test void listRejectsUnavailableActorEvenWithPreviouslyValidPrincipal() {
        replace(find(2), MemberRole.MEMBER, "LEFT");
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> service.list(member));
        assertThat(operations).isEmpty();
    }

    @Test void unsafeStoredIdsCannotLeakThroughListOrRoleResponse() {
        rows.add(new MemberStore.MemberState(14, 9007199254740992L, "ACTIVE", MemberRole.MEMBER, "ACTIVE", "Unsafe", null, NOW));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.list(owner));
        fails(ErrorCode.VALIDATION_FAILED, () -> service.changeRole(owner, 14, MemberRole.ADMIN, "r"));
        noWrites();
    }

    @Test void invitationRevocationFailureStopsFinalMemberSuccessAudit() {
        invitationRows.put(51L, invite(51, 2, "ACTIVE", NOW.plusSeconds(60)));
        when(inviteStore.revoke(eq(51L), any())).thenReturn(0);
        fails(ErrorCode.CONFLICT, () -> service.remove(owner, 12, "r"));
        assertThat(events).isEmpty();
    }

    @Test void missingConfigReadFailsSafelyWithoutGlobalWriteLock() {
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1)).when(auth).readAppConfig();
        fails(ErrorCode.LEDGER_STATE_CONFLICT, () -> service.list(owner));
        assertThat(operations).isEmpty();
    }

    void noWrites() {
        assertThat(operations).allMatch(op -> op.endsWith("lock"));
        assertThat(events).isEmpty();
        assertThat(liveSessions).hasSize(3);
        verifyNoInteractions(inviteStore, audit);
    }

    void fails(ErrorCode expected, Supplier<?> call) {
        assertThatThrownBy(call::get).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }

    static CurrentUser principal(long id, MemberRole role) { return new CurrentUser(id, 1, id + 10, role); }
    static MemberStore.MemberState row(long id, MemberRole role) {
        return new MemberStore.MemberState(id + 10, id, "ACTIVE", role, "ACTIVE", "Name" + id, "Alias" + id, Instant.EPOCH);
    }
    MemberStore.MemberState find(long userId) { return rows.stream().filter(row -> row.userId() == userId).findFirst().orElseThrow(); }
    void replace(MemberStore.MemberState old, MemberRole role, String status) {
        rows.set(rows.indexOf(old), new MemberStore.MemberState(old.memberId(), old.userId(), old.userStatus(), role,
                status, old.nickname(), old.displayName(), old.joinedAt()));
    }
    static InviteStore.InviteRow invite(long id, long creator, String status, Instant expiresAt) {
        return new InviteStore.InviteRow(id, 1, creator, Instant.EPOCH, expiresAt, status,
                status.equals("USED") ? 2L : null, status.equals("USED") ? NOW : null);
    }
}
