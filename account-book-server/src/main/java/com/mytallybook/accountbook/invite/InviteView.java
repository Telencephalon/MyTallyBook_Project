package com.mytallybook.accountbook.invite;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InviteView(long id, long createdBy, String createdByName, Instant createdAt,
                         Instant expiresAt, String status, Long usedBy, Instant usedAt) {}
