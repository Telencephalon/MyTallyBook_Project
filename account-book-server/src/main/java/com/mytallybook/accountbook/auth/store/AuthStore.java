package com.mytallybook.accountbook.auth.store;

import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;

import java.time.Instant;
import java.util.Optional;

public interface AuthStore {

    AppConfigState readAppConfig();

    AppConfigState lockAppConfig();

    Optional<LoginMembership> findLoginMembership(String openid);

    Optional<LoginMembership> lockLoginMembership(long userId);

    long insertUser(String openid, String unionid, String nickname, Instant now);

    void insertLedger(long ownerUserId, int maxMembers, Instant now);

    long insertOwnerMembership(long ownerUserId, Instant now);

    int insertDefaultCategories();

    int insertDefaultFundAccounts();

    void markInitialized(long expectedVersion, Instant now);

    void revokeAllSessions(long userId, Instant revokedAt);

    long insertSession(long userId, String tokenHash, Instant expiresAt, Instant now);

    void updateLastLogin(long userId, Instant now);

    int revokeSession(String tokenHash, Instant revokedAt);

    Optional<CurrentUser> findCurrentUserByTokenHash(String tokenHash, Instant now);

    Optional<UserProfileView> findUserProfile(long userId);

    void updateUserProfile(
            long userId,
            boolean nicknamePresent,
            String nickname,
            boolean avatarPresent,
            String avatarUrl,
            Instant now
    );

    Optional<LedgerView> findLedger(long ledgerId);

    record AppConfigState(boolean initialized, int maxUsers, long version) {
    }

    record LoginMembership(
            long userId,
            String userStatus,
            Long ledgerId,
            Long memberId,
            MemberRole role,
            String memberStatus,
            String ledgerStatus
    ) {
    }

    record UserProfileView(
            long userId,
            String nickname,
            String avatarUrl,
            long ledgerId,
            long memberId,
            MemberRole role,
            String displayName
    ) {
    }

    record LedgerView(
            long id,
            String name,
            String currency,
            String timezone,
            int maxMembers
    ) {
    }
}
