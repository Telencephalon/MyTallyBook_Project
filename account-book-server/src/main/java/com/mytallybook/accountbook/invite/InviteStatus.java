package com.mytallybook.accountbook.invite;
import java.time.Instant;
import java.util.Set;
/** One effective-state policy shared by reads and locked decisions. */
public final class InviteStatus {
    private InviteStatus() {}
    public static final Set<String> VALUES = Set.of("ACTIVE", "USED", "REVOKED", "EXPIRED");
    public static String effective(String stored, Instant expiresAt, boolean creatorIsManager, Instant now) {
        if (!"ACTIVE".equals(stored)) return stored;
        if (!expiresAt.isAfter(now)) return "EXPIRED";
        return creatorIsManager ? "ACTIVE" : "REVOKED";
    }
    public static String sql() {
        return """
            CASE WHEN i.status <> 'ACTIVE' THEN i.status
                 WHEN i.expires_at <= ? THEN 'EXPIRED'
                 WHEN u.status = 'ACTIVE' AND cm.status = 'ACTIVE'
                      AND cm.role IN ('OWNER','ADMIN') AND l.status = 'ACTIVE' THEN 'ACTIVE'
                 ELSE 'REVOKED' END
            """;
    }
}
