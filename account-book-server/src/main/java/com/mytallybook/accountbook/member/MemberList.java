package com.mytallybook.accountbook.member;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberList(List<MemberView> items, int activeCount, Integer maxMembers, long ownerUserId) {
    public MemberList {
        items = List.copyOf(items);
    }
}
