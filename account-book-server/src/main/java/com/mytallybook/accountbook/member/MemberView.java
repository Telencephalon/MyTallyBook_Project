package com.mytallybook.accountbook.member;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mytallybook.accountbook.security.MemberRole;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberView(long memberId, long userId, String nickname, String displayName,
                         MemberRole role, Instant joinedAt) {}
