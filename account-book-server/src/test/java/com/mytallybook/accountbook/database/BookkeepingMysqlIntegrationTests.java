package com.mytallybook.accountbook.database;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.auth.service.BootstrapService;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.invite.InviteService;
import com.mytallybook.accountbook.member.MemberService;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.DatabaseSessionTokenVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mytallybook.accountbook.database.ObservedMysqlDataSource.Phase.AFTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Opt-in destructive acceptance suite: guarded account_book_test ONLY.
 * Exercises real HTTP security, Spring service proxies, MySQL SQL and audit transactions.
 * No real WeChat calls, development schema, Task 9 statistics, capacity tests or outer test transaction.
 * The operator must still provide exclusive maintenance: ResourceLock cannot coordinate separate JVMs.
 */
@SpringBootTest(classes = {AccountBookServerApplication.class, BookkeepingMysqlIntegrationTests.TestBeans.class})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "BOOKKEEPING_MYSQL_GATE", matches = "task78")
@ResourceLock("account-book-test-schema")
@Execution(ExecutionMode.SAME_THREAD)
class BookkeepingMysqlIntegrationTests {
    private static final String BOOTSTRAP_KEY = "test-only-bookkeeping-bootstrap-2026";
    private static MysqlTestDatabaseSupport database;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObservedMysqlDataSource dataSource;
    @Autowired BootstrapService bootstrap;
    @Autowired DatabaseSessionTokenVerifier verifier;
    @Autowired InviteService invites;
    @Autowired MemberService members;
    private CurrentUser owner;
    private String token;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            database = MysqlTestDatabaseSupport.openResetSchema();
            database.migrate();
        } catch (RuntimeException | Error | SQLException failure) {
            if (database != null) {
                try {
                    database.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw new IllegalStateException("Unable to prepare exclusive bookkeeping test schema", failure);
        }
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.wechat.app-id", () -> "test-app-id");
        registry.add("app.wechat.app-secret", () -> "test-app-secret");
        registry.add("app.auth.bootstrap-key", () -> BOOTSTRAP_KEY);
        registry.add("app.auth.token-pepper", () -> "test-only-token-pepper-0123456789abcdef");
        registry.add("app.auth.session-ttl", () -> "30d");
        registry.add("app.auth.token-bytes", () -> "32");
    }

    @AfterAll
    static void cleanup() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void fixture() {
        dataSource.observer = ObservedMysqlDataSource.NONE;
        // The datasource can exist only after the exclusive test identity and reset authorization checks.
        for (String table : List.of("audit_log", "book_entry", "ledger_invite", "auth_session", "category",
                "fund_account", "ledger_member", "ledger", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE app_config SET initialized=FALSE,max_users=10,version=0 WHERE id=1");
        token = bootstrap.bootstrap("bookkeeping-owner", BOOTSTRAP_KEY, "fixture-bootstrap").token();
        owner = verifier.verify(token).orElseThrow();
    }

    // Catches bypassed MySQL collation uniqueness, mutable immutable fields and missing version predicates.
    @Test
    void catalogCollationImmutableFieldsAndVersionAreEnforced() throws Exception {
        long category = category("Café", "EXPENSE");
        call("POST", "/categories", Map.of("entryType", "EXPENSE", "name", "CAFE"), 409);
        call("PUT", "/categories/" + category,
                Map.of("name", "Café", "sortNo", 0, "status", "ACTIVE", "entryType", "INCOME"), 400);
        long account = account("Gate cash", "-2.30");
        JsonNode initial = call("GET", "/accounts/" + account, null, 200);
        assertThat(initial.path("currentBalance").asText()).isEqualTo("-2.30");
        long version = initial.path("version").asLong();
        Map<String, Object> update = Map.of(
                "name", "Gate cash renamed", "sortNo", 32767, "status", "DISABLED", "version", version);
        call("PUT", "/accounts/" + account, update, 200);
        call("PUT", "/accounts/" + account, update, 409);
        call("DELETE", "/accounts/" + account + "?version=" + version, null, 409);
        call("PUT", "/accounts/" + account,
                Map.of("name", "Bad", "sortNo", 0, "status", "ACTIVE", "version", version + 1,
                        "initialBalance", "3.00"), 400);
        call("POST", "/accounts",
                Map.of("name", "numeric rejected", "accountType", "CASH", "initialBalance", 12.30), 400);
    }

    // Catches duplicate inserts, overwrite-on-replay, hard deletion and balances that count deleted entries.
    @Test
    void idempotencyEditDeleteAndReferenceProtectionAgreeWithBalance() throws Exception {
        long expense = category("Gate expense", "EXPENSE");
        long income = category("Gate income", "INCOME");
        long account = account("Gate account", "10.00");
        String key = UUID.randomUUID().toString();
        Map<String, Object> input = entry("EXPENSE", "3.40", expense, account,
                "2026-09-01", "first", key);
        JsonNode created = call("POST", "/entries", input, 200);
        long id = created.path("id").asLong();
        input.put("amount", "9.99");
        assertThat(call("POST", "/entries", input, 200).path("amount").asText()).isEqualTo("3.40");
        assertThat(number("SELECT COUNT(*) FROM book_entry WHERE client_request_id=?", key)).isEqualTo(1);
        assertBalance(account, "6.60");
        Map<String, Object> edit = editable("INCOME", "20.10", income, account,
                "2026-09-02", "edited", created.path("version").asLong());
        JsonNode changed = call("PUT", "/entries/" + id, edit, 200);
        call("PUT", "/entries/" + id, edit, 409);
        assertBalance(account, "30.10");
        JsonNode replay = call("POST", "/entries", input, 200);
        assertThat(replay.path("amount").asText()).isEqualTo("20.10");
        call("DELETE", "/entries/" + id + "?version=" + changed.path("version").asLong(), null, 200);
        call("GET", "/entries/" + id, null, 404);
        call("POST", "/entries", input, 409);
        assertBalance(account, "10.00");
        assertThat(number("SELECT COUNT(*) FROM book_entry WHERE id=? AND deleted_at IS NOT NULL", id)).isEqualTo(1);
        call("DELETE", "/categories/" + income, null, 409);
        call("DELETE", "/accounts/" + account + "?version=0", null, 409);
    }

    // Catches accidental filtering of historical disabled references and literal LIKE wildcard errors.
    @Test
    void disabledUnchangedAssociationsAndLiteralKeywordRemainUsable() throws Exception {
        long category = category("Gate disabled", "EXPENSE");
        long account = account("Gate disabled account", "0.00");
        JsonNode created = call("POST", "/entries", entry("EXPENSE", "1.20", category, account,
                "9999-12-31", "literal %_\\ note", UUID.randomUUID().toString()), 200);
        call("PUT", "/categories/" + category,
                Map.of("name", "Gate disabled", "sortNo", 0, "status", "DISABLED"), 200);
        call("PUT", "/accounts/" + account,
                Map.of("name", "Gate disabled account", "sortNo", 0, "status", "DISABLED", "version", 0), 200);
        JsonNode changed = call("PUT", "/entries/" + created.path("id").asLong(),
                editable("EXPENSE", "2.20", category, account, "9999-12-31", "literal %_\\ note",
                        created.path("version").asLong()), 200);
        assertThat(changed.path("categoryStatus").asText()).isEqualTo("DISABLED");
        assertThat(changed.path("accountStatus").asText()).isEqualTo("DISABLED");
        call("POST", "/entries", entry("EXPENSE", "1.00", category, account,
                "2026-09-01", "denied", UUID.randomUUID().toString()), 400);
        JsonNode filtered = call("GET", "/entries?keyword=%25_%5C&dateFrom=9999-12-31&dateTo=9999-12-31",
                null, 200);
        assertThat(filtered.path("total").asLong()).isEqualTo(1);
        assertThat(call("GET", "/entries?keyword=nonexistent", null, 200).path("total").asLong()).isZero();
    }

    // Catches current-member-only history joins, acting on someone else's bill, or cross-actor UUID replay.
    @Test
    void formerCreatorsStayInHistoryAndCurrentAuthorityControlsMutations() throws Exception {
        String ownerToken = token;
        var invite = invites.create(owner, 24, "fixture-invite");
        String memberToken = invites.accept("historical-creator", invite.token(), "fixture-accept").token();
        CurrentUser member = verifier.verify(memberToken).orElseThrow();
        jdbc.update("UPDATE ledger_member SET display_name='Historical author' WHERE id=?", member.memberId());
        long category = category("Gate member", "EXPENSE");
        long account = account("Gate member account", "0.00");
        Map<String, Object> input = entry("EXPENSE", "4.50", category, account,
                "2026-09-03", "member note", UUID.randomUUID().toString());
        JsonNode owned = call("POST", "/entries", input, 200);
        token = memberToken;
        call("POST", "/entries", input, 409);
        call("PUT", "/entries/" + owned.path("id").asLong(),
                editable("EXPENSE", "7.00", category, account, "2026-09-03", "forbidden",
                        owned.path("version").asLong()), 403);
        call("POST", "/categories", Map.of("entryType", "EXPENSE", "name", "Forbidden"), 403);
        call("POST", "/entries", entry("EXPENSE", "1.25", category, account,
                "2026-09-03", "history", UUID.randomUUID().toString()), 200);
        token = ownerToken;
        members.remove(owner, member.memberId(), "fixture-remove");
        JsonNode creators = call("GET", "/entries/creators", null, 200);
        assertThat(creators.path("items").toString()).contains("Historical author");
        token = memberToken;
        call("GET", "/entries/creators", null, 401);
        token = ownerToken;
    }

    // Catches persistence of a successful entry note in audit details while proving the entry kept the note itself.
    @Test
    void successfulEntryAuditDoesNotPersistPrivateNoteText() throws Exception {
        long category = category("Gate audit privacy", "EXPENSE");
        long account = account("Gate audit privacy account", "0.00");
        String privateNote = "private-note-marker-6f7b4d2a";
        JsonNode created = call("POST", "/entries", entry("EXPENSE", "1.00", category, account,
                "2026-09-04", privateNote, UUID.randomUUID().toString()), 200);

        assertThat(jdbc.queryForObject("SELECT note FROM book_entry WHERE id=?", String.class,
                created.path("id").asLong())).isEqualTo(privateNote);
        String details = jdbc.queryForObject("""
                SELECT CAST(details_json AS CHAR) FROM audit_log
                WHERE action='ENTRY_CREATE' AND resource_type='BOOK_ENTRY' AND resource_id=?
                """, String.class, created.path("id").asLong());
        assertThat(details).isNotNull().doesNotContain(privateNote);
    }

    // Catches missing Spring transaction proxy boundaries or an independently committed audit insert.
    @Test
    void physicalRollbackRestoresDataAfterBusinessDmlAndAuditInsertFailures() throws Exception {
        long category = category("Gate rollback", "EXPENSE");
        long account = account("Gate rollback account", "20.00");
        for (String boundary : List.of(
                "insert into category", "insert into fund_account", "insert into book_entry", "insert into audit_log")) {
            Map<String, List<Map<String, Object>>> before = snapshot();
            AtomicBoolean reached = new AtomicBoolean();
            dataSource.observer = event -> {
                if (event.phase() == AFTER && event.sql().startsWith(boundary)) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(event.connection().getAutoCommit()).isFalse();
                    reached.set(true);
                    throw new IllegalStateException("test-injected-after-real-sql");
                }
            };
            try {
                if (boundary.contains("category")) {
                    call("POST", "/categories",
                            Map.of("name", "Rollback category", "entryType", "EXPENSE"), 500);
                } else if (boundary.contains("fund_account")) {
                    call("POST", "/accounts",
                            Map.of("name", "Rollback account", "accountType", "CASH", "initialBalance", "0.00"), 500);
                } else {
                    call("POST", "/entries", entry("EXPENSE", "2.50", category, account,
                            "2026-09-04", "private note must not be audited", UUID.randomUUID().toString()), 500);
                }
            } finally {
                dataSource.observer = ObservedMysqlDataSource.NONE;
            }
            assertThat(reached.get()).as("The real matching SQL ran before injection").isTrue();
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    // Catches a missing transaction boundary on entry update or soft delete after their business UPDATE succeeds.
    @Test
    void auditFailurePhysicallyRollsBackEntryEditAndSoftDelete() throws Exception {
        long category = category("Gate mutation rollback", "EXPENSE");
        long account = account("Gate mutation rollback account", "20.00");
        JsonNode created = call("POST", "/entries", entry("EXPENSE", "2.50", category, account,
                "2026-09-04", "original", UUID.randomUUID().toString()), 200);
        long id = created.path("id").asLong();
        long version = created.path("version").asLong();

        Map<String, List<Map<String, Object>>> beforeEdit = snapshot();
        AtomicBoolean editAuditReached = new AtomicBoolean();
        dataSource.observer = event -> {
            if (event.phase() == AFTER && event.sql().startsWith("insert into audit_log")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(event.connection().getAutoCommit()).isFalse();
                editAuditReached.set(true);
                throw new IllegalStateException("test-injected-after-entry-update-audit");
            }
        };
        try {
            call("PUT", "/entries/" + id,
                    editable("EXPENSE", "50.00", category, account, "2026-09-05", "changed", version), 500);
        } finally {
            dataSource.observer = ObservedMysqlDataSource.NONE;
        }
        assertThat(editAuditReached.get()).isTrue();
        assertThat(snapshot()).isEqualTo(beforeEdit);
        JsonNode unchanged = call("GET", "/entries/" + id, null, 200);
        assertThat(unchanged.path("amount").asText()).isEqualTo("2.50");
        assertThat(unchanged.path("entryDate").asText()).isEqualTo("2026-09-04");
        assertThat(unchanged.path("note").asText()).isEqualTo("original");
        assertThat(unchanged.path("version").asLong()).isEqualTo(version);
        assertBalance(account, "17.50");

        Map<String, List<Map<String, Object>>> beforeDelete = snapshot();
        AtomicBoolean deleteAuditReached = new AtomicBoolean();
        dataSource.observer = event -> {
            if (event.phase() == AFTER && event.sql().startsWith("insert into audit_log")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(event.connection().getAutoCommit()).isFalse();
                deleteAuditReached.set(true);
                throw new IllegalStateException("test-injected-after-entry-delete-audit");
            }
        };
        try {
            call("DELETE", "/entries/" + id + "?version=" + version, null, 500);
        } finally {
            dataSource.observer = ObservedMysqlDataSource.NONE;
        }
        assertThat(deleteAuditReached.get()).isTrue();
        assertThat(snapshot()).isEqualTo(beforeDelete);
        JsonNode stillPresent = call("GET", "/entries/" + id, null, 200);
        assertThat(stillPresent.path("version").asLong()).isEqualTo(version);
        assertThat(number("SELECT COUNT(*) FROM book_entry WHERE id=? AND deleted_at IS NULL", id)).isEqualTo(1);
        assertBalance(account, "17.50");
    }

    private long category(String name, String type) throws Exception {
        return call("POST", "/categories", Map.of("name", name, "entryType", type), 200)
                .path("id").asLong();
    }

    private long account(String name, String initial) throws Exception {
        return call("POST", "/accounts",
                Map.of("name", name, "accountType", "CASH", "initialBalance", initial), 200)
                .path("id").asLong();
    }

    private void assertBalance(long account, String expected) throws Exception {
        assertThat(call("GET", "/accounts/" + account, null, 200).path("currentBalance").asText())
                .isEqualTo(expected);
    }

    private static Map<String, Object> entry(
            String type,
            String amount,
            long category,
            long account,
            String date,
            String note,
            String key
    ) {
        Map<String, Object> body = editable(type, amount, category, account, date, note, 0);
        body.remove("version");
        body.put("clientRequestId", key);
        return body;
    }

    private static Map<String, Object> editable(
            String type,
            String amount,
            long category,
            long account,
            String date,
            String note,
            long version
    ) {
        return new LinkedHashMap<>(Map.of(
                "entryType", type,
                "amount", amount,
                "categoryId", category,
                "accountId", account,
                "entryDate", date,
                "note", note,
                "version", version
        ));
    }

    private JsonNode call(String method, String path, Map<String, Object> body, int status) throws Exception {
        var builder = gateRequest(method, path)
                .header("Authorization", "Bearer " + token)
                .header("X-Request-Id", "gate-" + UUID.randomUUID());
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        }
        var response = mvc.perform(builder).andReturn().getResponse();
        assertThat(response.getStatus()).as(method + " " + path + " HTTP status").isEqualTo(status);
        JsonNode envelope = json.readTree(response.getContentAsByteArray());
        if (status == 200) {
            assertThat(envelope.path("code").asText()).isEqualTo("OK");
            return envelope.path("data");
        }
        assertThat(envelope.path("code").asText()).isNotEmpty().isNotEqualTo("OK");
        return envelope;
    }

    static MockHttpServletRequestBuilder gateRequest(String method, String path) {
        // Paths already contain percent-encoded query values; the String overload encodes them again.
        return request(HttpMethod.valueOf(method), URI.create("/api/v1" + path));
    }

    private long number(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : List.of(
                "app_config", "app_user", "ledger", "ledger_member", "category", "fund_account",
                "book_entry", "audit_log")) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return rows;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        ObservedMysqlDataSource dataSource() {
            return new ObservedMysqlDataSource(Objects.requireNonNull(database));
        }

        @Bean
        @Primary
        Clock bookkeepingClock() {
            return Clock.fixed(Instant.parse("2026-09-06T02:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        @Primary
        WechatSessionClient bookkeepingWechat() {
            return code -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new WechatIdentity("openid-" + code, null);
            };
        }
    }
}
