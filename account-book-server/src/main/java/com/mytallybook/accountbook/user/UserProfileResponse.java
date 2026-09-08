package com.mytallybook.accountbook.user;

import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.security.MemberRole;

public record UserProfileResponse(
        long userId,
        String nickname,
        String avatarUrl,
        long ledgerId,
        long memberId,
        MemberRole role,
        String displayName
) {

    static UserProfileResponse from(AuthStore.UserProfileView profile) {
        return new UserProfileResponse(
                profile.userId(),
                profile.nickname(),
                profile.avatarUrl(),
                profile.ledgerId(),
                profile.memberId(),
                profile.role(),
                profile.displayName()
        );
    }
}
