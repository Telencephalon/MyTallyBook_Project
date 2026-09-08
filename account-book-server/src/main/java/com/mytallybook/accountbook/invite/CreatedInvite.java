package com.mytallybook.accountbook.invite;
import java.time.Instant;
public record CreatedInvite(long id, String token, Instant expiresAt, String status) {}
