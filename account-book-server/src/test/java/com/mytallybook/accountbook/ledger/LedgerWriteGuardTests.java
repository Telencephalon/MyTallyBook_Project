package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.support.TestTransactions;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerWriteGuardTests {
    private final AuthStore auth = mock(AuthStore.class);
    private final MemberStore members = mock(MemberStore.class);
    private final LedgerWriteGuard guard = new LedgerWriteGuard(auth, members);
    private final MemberStore.LedgerState ledger = new MemberStore.LedgerState(1, 7, 8, "ACTIVE", 2);

    @Test void requiresAnActualTransactionBeforeAnyDatabaseAccess() {
        assertThatThrownBy(guard::lock).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(auth, members);
    }

    @Test void missingConfigurationRowIsASafeConflictBeforeAnyLaterPersistence() {
        when(auth.lockAppConfig()).thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertThatThrownBy(() -> TestTransactions.template().execute(status -> guard.lock()))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.errorCode().name()).isEqualTo("LEDGER_STATE_CONFLICT");
                    assertThat(error.errorCode().httpStatus().value()).isEqualTo(409);
                });
        verify(auth).lockAppConfig();
        verifyNoMoreInteractions(auth);
        verifyNoInteractions(members);
    }

    @Test void unrelatedConfigurationDatabaseFailureIsNotMappedToStateConflict() {
        var failure = new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
        when(auth.lockAppConfig()).thenThrow(failure);
        assertThatThrownBy(() -> TestTransactions.template().execute(status -> guard.lock())).isSameAs(failure);
        verifyNoInteractions(members);
    }

    @Test void locksInOrderAndUsesFreshActorRoleAndTheLowestCapacity() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 6, 1));
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.lockMembers()).thenReturn(List.of(member(11, 7, MemberRole.OWNER, "ACTIVE"), member(12, 8, MemberRole.MEMBER, "ACTIVE")));
        var locked = TestTransactions.template().execute(status -> guard.lock());
        assertThat(locked.maxMembers()).isEqualTo(6);
        assertThat(locked.requireActor(new CurrentUser(8, 1, 12, MemberRole.ADMIN)).role()).isEqualTo(MemberRole.MEMBER);
        var order = inOrder(auth, members);
        order.verify(auth).lockAppConfig();
        order.verify(members).lockLedger();
        order.verify(members).lockMembers();
        assertThatThrownBy(() -> locked.requireActor(new CurrentUser(8, 2, 12, MemberRole.MEMBER))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> locked.requireActor(new CurrentUser(8, 1, 99, MemberRole.MEMBER))).isInstanceOf(BusinessException.class);
    }

    @Test void rejectsMissingDuplicateMismatchedOrInactiveOwner() {
        for (var invalid : List.of(
                List.of(member(11, 7, MemberRole.MEMBER, "ACTIVE")),
                List.of(member(11, 7, MemberRole.OWNER, "ACTIVE"), member(12, 8, MemberRole.OWNER, "ACTIVE")),
                List.of(member(12, 8, MemberRole.OWNER, "ACTIVE")),
                List.of(member(11, 7, MemberRole.OWNER, "REMOVED")),
                List.of(new MemberStore.MemberState(11, 7, "DISABLED", MemberRole.OWNER, "ACTIVE", "owner", null, Instant.EPOCH)))) {
            assertThatThrownBy(() -> guard.assertOwnerInvariant(ledger, invalid))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode().name()).isEqualTo("LEDGER_STATE_CONFLICT"));
        }
        assertThatCode(() -> guard.assertOwnerInvariant(ledger, List.of(member(11, 7, MemberRole.OWNER, "ACTIVE")))).doesNotThrowAnyException();
    }

    @Test void rejectsUninitializedInvalidCapacityMissingAndInactiveLedger() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(false, 10, 1));
        assertLockError("SYSTEM_NOT_INITIALIZED");
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 0, 1));
        assertLockError("LEDGER_STATE_CONFLICT");
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(members.lockLedger()).thenReturn(Optional.empty());
        assertLockError("LEDGER_STATE_CONFLICT");
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 7, 10, "DISABLED", 1)));
        assertLockError("LEDGER_STATE_CONFLICT");
    }

    private void assertLockError(String code) {
        assertThatThrownBy(() -> TestTransactions.template().execute(status -> guard.lock()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode().name()).isEqualTo(code));
    }

    @Test void rejectsOutOfRangeConfigurationBeforeAuthorizingWrites() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 11, 1));
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.lockMembers()).thenReturn(List.of(member(11, 7, MemberRole.OWNER, "ACTIVE")));
        assertLockError("LEDGER_STATE_CONFLICT");
    }

    @Test void rejectsOutOfRangeLedgerCapacityBeforeAuthorizingWrites() {
        when(auth.lockAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 7, 11, "ACTIVE", 1)));
        when(members.lockMembers()).thenReturn(List.of(member(11, 7, MemberRole.OWNER, "ACTIVE")));
        assertLockError("LEDGER_STATE_CONFLICT");
    }

    @Test void removedAndDisabledActorsCannotUseStaleIdentity() {
        var locked = new LedgerWriteGuard.LockedLedger(new AuthStore.AppConfigState(true, 10, 1), ledger,
                List.of(member(11, 7, MemberRole.OWNER, "ACTIVE"), member(12, 8, MemberRole.MEMBER, "REMOVED"),
                        new MemberStore.MemberState(13, 9, "DISABLED", MemberRole.ADMIN, "ACTIVE", "name", null, Instant.EPOCH)));
        assertThatThrownBy(() -> locked.requireActor(new CurrentUser(8, 1, 12, MemberRole.ADMIN))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> locked.requireActor(new CurrentUser(9, 1, 13, MemberRole.ADMIN))).isInstanceOf(BusinessException.class);
    }
    private static MemberStore.MemberState member(long id, long userId, MemberRole role, String status) {
        return new MemberStore.MemberState(id, userId, "ACTIVE", role, status, "nickname", null, Instant.EPOCH);
    }
}
