package com.mytallybook.accountbook.member.store;

import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcMemberStore implements MemberStore {
    private static final String LEDGER_SQL = """
            SELECT id, owner_user_id, max_members, status, version
            FROM ledger WHERE id = 1
            """;
    private static final String MEMBERS_SQL = """
            SELECT lm.id AS member_id, lm.user_id, u.status AS user_status,
                   lm.role, lm.status AS member_status, u.nickname, lm.display_name, lm.joined_at
            FROM ledger_member lm
            JOIN app_user u ON u.id = lm.user_id
            WHERE lm.ledger_id = 1
            ORDER BY lm.id
            """;
    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcMemberStore(ObjectProvider<JdbcTemplate> provider) { this(provider::getIfAvailable); }
    JdbcMemberStore(JdbcTemplate jdbc) { this(() -> jdbc); }
    private JdbcMemberStore(Supplier<JdbcTemplate> supplier) { this.jdbcTemplateSupplier = supplier; }

    @Override public Optional<LedgerState> lockLedger() {
        return jdbc().query(LEDGER_SQL + " FOR UPDATE", ledgerMapper()).stream().findFirst();
    }
    @Override public Optional<LedgerState> readLedger() {
        return jdbc().query(LEDGER_SQL, ledgerMapper()).stream().findFirst();
    }
    @Override public List<MemberState> lockMembers() {
        return jdbc().query(MEMBERS_SQL + " FOR UPDATE", memberMapper());
    }
    @Override public List<MemberState> readMembers() {
        return jdbc().query(MEMBERS_SQL, memberMapper());
    }
    @Override public Optional<UserState> lockUserByOpenid(String openid) {
        return jdbc().query("SELECT id, status FROM app_user WHERE openid = ? FOR UPDATE",
                (row, index) -> new UserState(row.getLong("id"), row.getString("status")), openid)
                .stream().findFirst();
    }
    private static RowMapper<LedgerState> ledgerMapper() {
        return (row, index) -> new LedgerState(row.getLong("id"), row.getLong("owner_user_id"),
                row.getInt("max_members"), row.getString("status"), row.getLong("version"));
    }
    @Override public long insertMember(long userId, java.time.Instant joinedAt) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        int changed = jdbc().update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO ledger_member (ledger_id, user_id, role, status, joined_at) VALUES (1, ?, 'MEMBER', 'ACTIVE', ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setTimestamp(2, java.sql.Timestamp.from(joinedAt));
            return statement;
        }, keys);
        if (changed != 1 || keys.getKey() == null) throw new IllegalStateException("Member insert failed");
        return keys.getKey().longValue();
    }
    @Override public int reactivateMember(long memberId, long userId, String expectedStatus, java.time.Instant joinedAt) {
        return jdbc().update("""
                UPDATE ledger_member SET role = 'MEMBER', status = 'ACTIVE', joined_at = ?, removed_at = NULL
                WHERE ledger_id = 1 AND id = ? AND user_id = ? AND status = ? AND status IN ('REMOVED','LEFT')
                """, java.sql.Timestamp.from(joinedAt), memberId, userId, expectedStatus);
    }
    @Override
    public int changeRole(long memberId, MemberRole expectedRole, MemberRole role) {
        return jdbc().update("""
                UPDATE ledger_member SET role = ?
                WHERE ledger_id = 1 AND id = ? AND role = ? AND status = 'ACTIVE'
                """, role.name(), memberId, expectedRole.name());
    }

    @Override
    public int remove(long memberId, MemberRole expectedRole, String status, java.time.Instant removedAt) {
        return jdbc().update("""
                UPDATE ledger_member SET status = ?, removed_at = ?
                WHERE ledger_id = 1 AND id = ? AND role = ? AND status = 'ACTIVE'
                """, status, java.sql.Timestamp.from(removedAt), memberId, expectedRole.name());
    }

    @Override
    public int transferOwner(long expectedOwnerUserId, long ownerUserId, long expectedVersion) {
        return jdbc().update("""
                UPDATE ledger SET owner_user_id = ?, version = version + 1
                WHERE id = 1 AND owner_user_id = ? AND version = ? AND status = 'ACTIVE'
                """, ownerUserId, expectedOwnerUserId, expectedVersion);
    }

    private static RowMapper<MemberState> memberMapper() {
        return (row, index) -> new MemberState(row.getLong("member_id"), row.getLong("user_id"),
                row.getString("user_status"), MemberRole.valueOf(row.getString("role")),
                row.getString("member_status"), row.getString("nickname"), row.getString("display_name"),
                row.getTimestamp("joined_at").toInstant());
    }
    private JdbcTemplate jdbc() {
        var jdbc = jdbcTemplateSupplier.get();
        if (jdbc == null) { throw new IllegalStateException("Member persistence requires a datasource"); }
        return jdbc;
    }
}
