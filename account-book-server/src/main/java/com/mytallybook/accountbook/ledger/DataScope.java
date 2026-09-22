package com.mytallybook.accountbook.ledger;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.validation.BookkeepingValidation;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.MemberRole;

/** Account-owned bookkeeping data inside the invitation-only shared ledger. */
public final class DataScope {
    private DataScope() {}

    public static Long creator(MemberStore.MemberState actor, Long requested) {
        if (actor == null || actor.role() == null) throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        if (requested != null) BookkeepingValidation.safeId(requested);
        if (actor.role() == MemberRole.OWNER) return requested;
        long ownId = BookkeepingValidation.safeId(actor.userId());
        if (requested != null && requested != ownId) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        return ownId;
    }

    public static boolean canAccess(MemberStore.MemberState actor, long createdBy) {
        return actor != null && actor.role() != null
                && (actor.role() == MemberRole.OWNER || actor.userId() == createdBy);
    }
}
