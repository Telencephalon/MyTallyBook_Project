package com.mytallybook.accountbook.auth.store;

import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcAuthStore implements AuthStore {

    private static final long FIXED_LEDGER_ID = 1L;

    private static final String READ_CONFIG_SQL = """
            SELECT initialized, max_users, version
            FROM app_config
            WHERE id = 1
            """;

    private static final String LOCK_CONFIG_SQL = READ_CONFIG_SQL + " FOR UPDATE";

    private static final String FIND_LOGIN_MEMBERSHIP_SQL = """
            SELECT
                u.id AS user_id,
                u.status AS user_status,
                lm.ledger_id,
                lm.id AS member_id,
                lm.role,
                lm.status AS member_status,
                l.status AS ledger_status
            FROM app_user u
            LEFT JOIN ledger_member lm
              ON lm.user_id = u.id
             AND lm.ledger_id = 1
            LEFT JOIN ledger l
              ON l.id = lm.ledger_id
            WHERE u.openid = ?
            """;

    private static final String INSERT_USER_SQL = """
            INSERT INTO app_user (
                openid, unionid, nickname, status,
                last_login_at, created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
            """;

    private static final String INSERT_LEDGER_SQL = """
            INSERT INTO ledger (
                id, name, currency, timezone, owner_user_id,
                max_members, status, created_at, updated_at
            ) VALUES (1, '共享账本', 'CNY', 'Asia/Shanghai', ?, ?, 'ACTIVE', ?, ?)
            """;

    private static final String INSERT_OWNER_MEMBERSHIP_SQL = """
            INSERT INTO ledger_member (
                ledger_id, user_id, role, status, joined_at
            ) VALUES (1, ?, 'OWNER', 'ACTIVE', ?)
            """;

    private static final String INSERT_CATEGORY_SQL = """
            INSERT INTO category (
                ledger_id, entry_type, name, sort_no, system_default, status
            ) VALUES (1, ?, ?, ?, TRUE, 'ACTIVE')
            """;

    private static final String INSERT_FUND_ACCOUNT_SQL = """
            INSERT INTO fund_account (
                ledger_id, name, account_type, initial_balance, sort_no, status
            ) VALUES (1, ?, ?, 0.00, ?, 'ACTIVE')
            """;

    private static final String MARK_INITIALIZED_SQL = """
            UPDATE app_config
            SET initialized = TRUE,
                version = version + 1,
                updated_at = ?
            WHERE id = 1
              AND initialized = FALSE
              AND version = ?
            """;

    private static final String REVOKE_ALL_SESSIONS_SQL = """
            UPDATE auth_session
            SET revoked_at = ?
            WHERE user_id = ?
              AND revoked_at IS NULL
            """;

    private static final String INSERT_SESSION_SQL = """
            INSERT INTO auth_session (
                user_id, token_hash, expires_at, created_at
            ) VALUES (?, ?, ?, ?)
            """;

    private static final String UPDATE_LAST_LOGIN_SQL = """
            UPDATE app_user
            SET last_login_at = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
            """;

    private static final String REVOKE_SESSION_SQL = """
            UPDATE auth_session
            SET revoked_at = ?
            WHERE token_hash = ?
              AND revoked_at IS NULL
            """;

    private static final String FIND_CURRENT_USER_SQL = """
            SELECT
                u.id AS user_id,
                l.id AS ledger_id,
                lm.id AS member_id,
                lm.role
            FROM auth_session s
            JOIN app_user u ON u.id = s.user_id
            JOIN ledger_member lm ON lm.user_id = u.id
            JOIN ledger l ON l.id = lm.ledger_id
            WHERE s.token_hash = ?
              AND s.revoked_at IS NULL
              AND s.expires_at > ?
              AND u.status = 'ACTIVE'
              AND lm.status = 'ACTIVE'
              AND l.status = 'ACTIVE'
              AND l.id = 1
            """;

    private static final String FIND_USER_PROFILE_SQL = """
            SELECT
                u.id AS user_id,
                u.nickname,
                u.avatar_url,
                l.id AS ledger_id,
                lm.id AS member_id,
                lm.role,
                lm.display_name
            FROM app_user u
            JOIN ledger_member lm ON lm.user_id = u.id
            JOIN ledger l ON l.id = lm.ledger_id
            WHERE u.id = ?
              AND u.status = 'ACTIVE'
              AND lm.status = 'ACTIVE'
              AND l.status = 'ACTIVE'
              AND l.id = 1
            """;

    private static final String UPDATE_USER_PROFILE_SQL = """
            UPDATE app_user
            SET nickname = CASE WHEN ? THEN ? ELSE nickname END,
                avatar_url = CASE WHEN ? THEN ? ELSE avatar_url END,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
            """;

    private static final String FIND_LEDGER_SQL = """
            SELECT id, name, currency, timezone, max_members
            FROM ledger
            WHERE id = ?
              AND status = 'ACTIVE'
              AND id = 1
            """;

    private static final List<Object[]> DEFAULT_CATEGORIES = List.of(
            category("EXPENSE", "餐饮", 10),
            category("EXPENSE", "交通", 20),
            category("EXPENSE", "购物", 30),
            category("EXPENSE", "居住", 40),
            category("EXPENSE", "医疗", 50),
            category("EXPENSE", "教育", 60),
            category("EXPENSE", "娱乐", 70),
            category("EXPENSE", "人情", 80),
            category("EXPENSE", "其他", 90),
            category("INCOME", "工资", 10),
            category("INCOME", "奖金", 20),
            category("INCOME", "理财", 30),
            category("INCOME", "红包", 40),
            category("INCOME", "退款", 50),
            category("INCOME", "其他", 60)
    );

    private static final List<Object[]> DEFAULT_FUND_ACCOUNTS = List.of(
            account("微信", "WECHAT", 10),
            account("支付宝", "ALIPAY", 20),
            account("现金", "CASH", 30),
            account("银行卡", "BANK", 40)
    );

    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcAuthStore(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this(jdbcTemplateProvider::getIfAvailable);
    }

    JdbcAuthStore(JdbcTemplate jdbcTemplate) {
        this(() -> jdbcTemplate);
    }

    private JdbcAuthStore(Supplier<JdbcTemplate> jdbcTemplateSupplier) {
        this.jdbcTemplateSupplier = jdbcTemplateSupplier;
    }

    @Override
    public AppConfigState readAppConfig() {
        return requireConfig(jdbc().queryForObject(READ_CONFIG_SQL, configRowMapper()));
    }

    @Override
    public AppConfigState lockAppConfig() {
        return requireConfig(jdbc().queryForObject(LOCK_CONFIG_SQL, configRowMapper()));
    }

    @Override
    public Optional<LoginMembership> findLoginMembership(String openid) {
        return loginMembership(FIND_LOGIN_MEMBERSHIP_SQL, openid);
    }

    @Override
    public Optional<LoginMembership> lockLoginMembership(long userId) {
        return loginMembership(FIND_LOGIN_MEMBERSHIP_SQL.replace("WHERE u.openid = ?", "WHERE u.id = ?")
                + " FOR UPDATE", userId);
    }

    private Optional<LoginMembership> loginMembership(String sql, Object identity) {
        List<LoginMembership> results = jdbc().query(
                sql,
                (resultSet, rowNumber) -> new LoginMembership(
                        resultSet.getLong("user_id"),
                        resultSet.getString("user_status"),
                        nullableLong(resultSet, "ledger_id"),
                        nullableLong(resultSet, "member_id"),
                        memberRole(resultSet.getString("role")),
                        resultSet.getString("member_status"),
                        resultSet.getString("ledger_status")
                ),
                identity
        );
        return results.stream().findFirst();
    }

    @Override
    public long insertUser(String openid, String unionid, String nickname, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return insertAndReturnKey(INSERT_USER_SQL, statement -> {
            statement.setString(1, openid);
            statement.setString(2, unionid);
            statement.setString(3, nickname);
            statement.setTimestamp(4, timestamp);
            statement.setTimestamp(5, timestamp);
            statement.setTimestamp(6, timestamp);
        });
    }

    @Override
    public void insertLedger(long ownerUserId, int maxMembers, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc().update(INSERT_LEDGER_SQL, new Object[]{
                ownerUserId,
                maxMembers,
                timestamp,
                timestamp
        });
    }

    @Override
    public long insertOwnerMembership(long ownerUserId, Instant now) {
        return insertAndReturnKey(INSERT_OWNER_MEMBERSHIP_SQL, statement -> {
            statement.setLong(1, ownerUserId);
            statement.setTimestamp(2, Timestamp.from(now));
        });
    }

    @Override
    public int insertDefaultCategories() {
        return Arrays.stream(jdbc().batchUpdate(INSERT_CATEGORY_SQL, DEFAULT_CATEGORIES)).sum();
    }

    @Override
    public int insertDefaultFundAccounts() {
        return Arrays.stream(jdbc().batchUpdate(INSERT_FUND_ACCOUNT_SQL, DEFAULT_FUND_ACCOUNTS)).sum();
    }

    @Override
    public void markInitialized(long expectedVersion, Instant now) {
        int updated = jdbc().update(
                MARK_INITIALIZED_SQL,
                new Object[]{Timestamp.from(now), expectedVersion}
        );
        requireSingleRow(updated, "Unable to mark application initialized");
    }

    @Override
    public void revokeAllSessions(long userId, Instant revokedAt) {
        jdbc().update(
                REVOKE_ALL_SESSIONS_SQL,
                new Object[]{Timestamp.from(revokedAt), userId}
        );
    }

    @Override
    public long insertSession(long userId, String tokenHash, Instant expiresAt, Instant now) {
        return insertAndReturnKey(INSERT_SESSION_SQL, statement -> {
            statement.setLong(1, userId);
            statement.setString(2, tokenHash);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.setTimestamp(4, Timestamp.from(now));
        });
    }

    @Override
    public void updateLastLogin(long userId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbc().update(
                UPDATE_LAST_LOGIN_SQL,
                new Object[]{timestamp, timestamp, userId}
        );
        requireSingleRow(updated, "Unable to update last login");
    }

    @Override
    public int revokeSession(String tokenHash, Instant revokedAt) {
        return jdbc().update(
                REVOKE_SESSION_SQL,
                new Object[]{Timestamp.from(revokedAt), tokenHash}
        );
    }

    @Override
    public Optional<CurrentUser> findCurrentUserByTokenHash(String tokenHash, Instant now) {
        List<CurrentUser> results = jdbc().query(
                FIND_CURRENT_USER_SQL,
                (resultSet, rowNumber) -> new CurrentUser(
                        resultSet.getLong("user_id"),
                        resultSet.getLong("ledger_id"),
                        resultSet.getLong("member_id"),
                        MemberRole.valueOf(resultSet.getString("role"))
                ),
                new Object[]{tokenHash, Timestamp.from(now)}
        );
        return results.stream().findFirst();
    }

    @Override
    public Optional<UserProfileView> findUserProfile(long userId) {
        List<UserProfileView> results = jdbc().query(
                FIND_USER_PROFILE_SQL,
                (resultSet, rowNumber) -> new UserProfileView(
                        resultSet.getLong("user_id"),
                        resultSet.getString("nickname"),
                        resultSet.getString("avatar_url"),
                        resultSet.getLong("ledger_id"),
                        resultSet.getLong("member_id"),
                        MemberRole.valueOf(resultSet.getString("role")),
                        resultSet.getString("display_name")
                ),
                userId
        );
        return results.stream().findFirst();
    }

    @Override
    public void updateUserProfile(
            long userId,
            boolean nicknamePresent,
            String nickname,
            boolean avatarPresent,
            String avatarUrl,
            Instant now
    ) {
        int updated = jdbc().update(UPDATE_USER_PROFILE_SQL, new Object[]{
                nicknamePresent,
                nickname,
                avatarPresent,
                avatarUrl,
                Timestamp.from(now),
                userId
        });
        requireSingleRow(updated, "Unable to update user profile");
    }

    @Override
    public Optional<LedgerView> findLedger(long ledgerId) {
        List<LedgerView> results = jdbc().query(
                FIND_LEDGER_SQL,
                (resultSet, rowNumber) -> new LedgerView(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("currency"),
                        resultSet.getString("timezone"),
                        resultSet.getInt("max_members")
                ),
                ledgerId
        );
        return results.stream().findFirst();
    }

    private long insertAndReturnKey(String sql, StatementBinder binder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbc().update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );
            binder.bind(statement);
            return statement;
        }, keyHolder);
        requireSingleRow(inserted, "Unable to insert database row");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Database did not return a generated key");
        }
        return key.longValue();
    }

    private JdbcTemplate jdbc() {
        JdbcTemplate jdbcTemplate = jdbcTemplateSupplier.get();
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Authentication persistence requires a datasource");
        }
        return jdbcTemplate;
    }

    private static RowMapper<AppConfigState> configRowMapper() {
        return (resultSet, rowNumber) -> new AppConfigState(
                resultSet.getBoolean("initialized"),
                resultSet.getInt("max_users"),
                resultSet.getLong("version")
        );
    }

    private static AppConfigState requireConfig(AppConfigState state) {
        return Objects.requireNonNull(state, "app_config singleton row is missing");
    }

    private static void requireSingleRow(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static MemberRole memberRole(String role) {
        return role == null ? null : MemberRole.valueOf(role);
    }

    private static Object[] category(String entryType, String name, int sortNo) {
        return new Object[]{entryType, name, sortNo};
    }

    private static Object[] account(String name, String accountType, int sortNo) {
        return new Object[]{name, accountType, sortNo};
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
