package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerReadGuardTests {
    @Test void activeActorIsResolvedFromLatestConsistentSnapshot() {
        var auth = mock(AuthStore.class); var members = mock(MemberStore.class);
        var owner = row(11, 1, MemberRole.OWNER); var actor = row(12, 2, MemberRole.MEMBER);
        when(auth.readAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(members.readLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 1, 10, "ACTIVE", 1)));
        when(members.readMembers()).thenReturn(List.of(owner, actor));
        assertEquals(actor, new LedgerReadGuard(auth, members, new LedgerWriteGuard(auth, members))
                .requireActor(new CurrentUser(2, 1, 12, MemberRole.ADMIN)));
    }

    @Test void removedOrMismatchedActorIsRejected() {
        var auth = mock(AuthStore.class); var members = mock(MemberStore.class);
        when(auth.readAppConfig()).thenReturn(new AuthStore.AppConfigState(true, 10, 1));
        when(members.readLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 1, 10, "ACTIVE", 1)));
        when(members.readMembers()).thenReturn(List.of(row(11, 1, MemberRole.OWNER)));
        var guard = new LedgerReadGuard(auth, members, new LedgerWriteGuard(auth, members));
        assertThrows(BusinessException.class, () -> guard.requireActor(new CurrentUser(2, 1, 12, MemberRole.MEMBER)));
    }

    static MemberStore.MemberState row(long memberId, long userId, MemberRole role) {
        return new MemberStore.MemberState(memberId, userId, "ACTIVE", role, "ACTIVE", "n", null, Instant.EPOCH);
    }
}
