package com.mytallybook.accountbook.member;

import java.util.List;

public record MemberList(List<MemberView> items, int activeCount, int maxMembers, long ownerUserId) {
    public MemberList {
        items = List.copyOf(items);
    }
}
