package com.mytallybook.accountbook.invite.store;
import com.mytallybook.accountbook.invite.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
@Repository
public class JdbcInviteStore implements InviteStore {
    private final Supplier<JdbcTemplate> supplier;
    @Autowired public JdbcInviteStore(ObjectProvider<JdbcTemplate> provider) { this.supplier=provider::getIfAvailable; }
    JdbcInviteStore(JdbcTemplate jdbc) { this.supplier=()->jdbc; }
    private JdbcTemplate jdbc() {
        var value=supplier.get();
        if(value==null) throw new IllegalStateException("Invitation persistence requires a datasource");
        return value;
    }
    private static final String ROWS="SELECT id, ledger_id, created_by, created_at, expires_at, status, used_by, used_at FROM ledger_invite WHERE ledger_id = 1";
    private static final String SNAPSHOT="""
        SELECT i.id, i.created_by, u.nickname AS created_by_name, i.created_at, i.expires_at,
               i.used_by, i.used_at,
        """+InviteStatus.sql()+"""
         AS effective_status
        FROM ledger_invite i
        JOIN app_user u ON u.id = i.created_by
        JOIN ledger l ON l.id = i.ledger_id
        LEFT JOIN ledger_member cm ON cm.ledger_id = i.ledger_id AND cm.user_id = i.created_by
        WHERE i.ledger_id = 1
        """;
    @Override public long insert(String hash,long creator,Instant now,Instant expiry) {
        var keys=new GeneratedKeyHolder();
        int count=jdbc().update(connection-> {
            var statement=connection.prepareStatement("""
                INSERT INTO ledger_invite (ledger_id, token_hash, created_by, created_at, expires_at, status)
                VALUES (1, ?, ?, ?, ?, 'ACTIVE')
                """,Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,hash); statement.setLong(2,creator);
            statement.setTimestamp(3,Timestamp.from(now)); statement.setTimestamp(4,Timestamp.from(expiry));
            return statement;
        },keys);
        if(count!=1 || keys.getKey()==null) throw new IllegalStateException("Invitation insert failed");
        return keys.getKey().longValue();
    }
    @Override public Optional<InviteRow> lockById(long id) {
        return jdbc().query(ROWS+" AND id = ? FOR UPDATE",rowMapper(),id).stream().findFirst();
    }
    @Override public Optional<InviteRow> lockByHash(String hash) {
        return jdbc().query(ROWS+" AND token_hash = ? FOR UPDATE",rowMapper(),hash).stream().findFirst();
    }
    @Override public List<InviteRow> lockCreatedBy(long userId) {
        return jdbc().query(ROWS+" AND created_by = ? AND status = 'ACTIVE' ORDER BY id FOR UPDATE",rowMapper(),userId);
    }
    @Override public int revoke(long id,Instant now) {
        return jdbc().update("UPDATE ledger_invite SET status = 'REVOKED' WHERE ledger_id = 1 AND id = ? AND status = 'ACTIVE' AND expires_at > ?",id,Timestamp.from(now));
    }
    @Override public int use(long id,long userId,Instant now) {
        return jdbc().update("UPDATE ledger_invite SET status = 'USED', used_by = ?, used_at = ? WHERE ledger_id = 1 AND id = ? AND status = 'ACTIVE' AND expires_at > ?",userId,Timestamp.from(now),id,Timestamp.from(now));
    }
    @Override public long count(String status,Instant now) {
        List<Object> args=arguments(status,now);
        Long count=jdbc().queryForObject("SELECT COUNT(*) FROM ("+SNAPSHOT+") snapshot"+filter(status),Long.class,args.toArray());
        return Objects.requireNonNull(count);
    }
    @Override public List<InviteView> list(String status,Instant now,int limit,int offset) {
        List<Object> args=arguments(status,now); args.add(limit); args.add(offset);
        return jdbc().query("SELECT * FROM ("+SNAPSHOT+") snapshot"+filter(status)+" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (row,index)->new InviteView(row.getLong("id"),row.getLong("created_by"),row.getString("created_by_name"),
                        row.getTimestamp("created_at").toInstant(),row.getTimestamp("expires_at").toInstant(),
                        row.getString("effective_status"),row.getObject("used_by",Long.class),instant(row,"used_at")),args.toArray());
    }
    private static String filter(String status) { return status==null?"":" WHERE effective_status = ?"; }
    private static List<Object> arguments(String status,Instant now) {
        List<Object> args=new ArrayList<>(); args.add(Timestamp.from(now)); if(status!=null) args.add(status); return args;
    }
    private static RowMapper<InviteRow> rowMapper() {
        return (row,index)->new InviteRow(row.getLong("id"),row.getLong("ledger_id"),row.getLong("created_by"),
                row.getTimestamp("created_at").toInstant(),row.getTimestamp("expires_at").toInstant(),row.getString("status"),
                row.getObject("used_by",Long.class),instant(row,"used_at"));
    }
    private static Instant instant(ResultSet row,String name) throws SQLException {
        Timestamp value=row.getTimestamp(name); return value==null?null:value.toInstant();
    }
}
