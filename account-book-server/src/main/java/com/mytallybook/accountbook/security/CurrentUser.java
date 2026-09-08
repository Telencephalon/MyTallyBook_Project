package com.mytallybook.accountbook.security;

public record CurrentUser(
        long userId,
        long ledgerId,
        long memberId,
        MemberRole role
) {
}
