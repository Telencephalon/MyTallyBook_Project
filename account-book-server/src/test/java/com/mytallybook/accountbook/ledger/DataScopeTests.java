package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DataScopeTests {
    @Test void ownerMayReadAllOrOneCreator() {
        assertNull(DataScope.creator(actor(MemberRole.OWNER), null));
        assertEquals(9L, DataScope.creator(actor(MemberRole.OWNER), 9L));
        assertTrue(DataScope.canAccess(actor(MemberRole.OWNER), 9));
    }
    @Test void everyNonOwnerIsPersonalOnly() {
        for (var role : new MemberRole[]{MemberRole.MEMBER, MemberRole.ADMIN}) {
            assertEquals(2L, DataScope.creator(actor(role), null));
            assertEquals(2L, DataScope.creator(actor(role), 2L));
            assertThrows(BusinessException.class, () -> DataScope.creator(actor(role), 9L));
            assertFalse(DataScope.canAccess(actor(role), 9));
            assertTrue(DataScope.canAccess(actor(role), 2));
        }
    }
    @Test void invalidScopeCannotBecomeUnrestricted() {
        assertThrows(BusinessException.class, () -> DataScope.creator(actor(MemberRole.OWNER), 0L));
        assertThrows(BusinessException.class, () -> DataScope.creator(null, null));
    }
    private static MemberStore.MemberState actor(MemberRole role) {
        return new MemberStore.MemberState(12, 2, "ACTIVE", role, "ACTIVE", "n", null, Instant.EPOCH);
    }
}
