package com.mytallybook.accountbook.member.store;

import com.mytallybook.accountbook.security.MemberRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemberStore {
    Optional<LedgerState> lockLedger();
    List<MemberState> lockMembers();
    Optional<UserState> lockUserByOpenid(String openid);
    Optional<LedgerState> readLedger();
    List<MemberState> readMembers();
    long insertMember(long userId, Instant joinedAt);
    int reactivateMember(long memberId, long userId, String expectedStatus, Instant joinedAt);
    int changeRole(long memberId, MemberRole expectedRole, MemberRole role);
    int remove(long memberId, MemberRole expectedRole, String status, Instant removedAt);
    int transferOwner(long expectedOwnerUserId, long ownerUserId, long expectedVersion);

    record LedgerState(long id, long ownerUserId, int maxMembers, String status, long version) {}
    record MemberState(long memberId, long userId, String userStatus, MemberRole role,
                       String status, String nickname, String displayName, Instant joinedAt) {}
    record UserState(long id, String status) {}
}
