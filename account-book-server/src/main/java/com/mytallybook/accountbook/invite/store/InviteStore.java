package com.mytallybook.accountbook.invite.store;
import com.mytallybook.accountbook.invite.InviteView;
import java.time.Instant;
import java.util.*;
public interface InviteStore {
    long insert(String tokenHash, long createdBy, Instant createdAt, Instant expiresAt);
    Optional<InviteRow> lockById(long id);
    Optional<InviteRow> lockByHash(String hash);
    List<InviteRow> lockCreatedBy(long userId);
    int revoke(long id, Instant now);
    int use(long id, long userId, Instant now);
    long count(String status, Instant now);
    List<InviteView> list(String status, Instant now, int limit, int offset);
    record InviteRow(long id, long ledgerId, long createdBy, Instant createdAt,
                     Instant expiresAt, String status, Long usedBy, Instant usedAt) {}
}
