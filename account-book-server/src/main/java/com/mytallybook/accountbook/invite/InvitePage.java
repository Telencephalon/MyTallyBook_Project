package com.mytallybook.accountbook.invite;
import java.util.List;
public record InvitePage(List<InviteView> items, int page, int pageSize, long total) {
    public InvitePage { items = List.copyOf(items); }
}
