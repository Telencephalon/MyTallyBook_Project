package com.mytallybook.accountbook.database;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.auth.service.AuthResult;
import com.mytallybook.accountbook.auth.service.AuthService;
import com.mytallybook.accountbook.auth.service.AuthState;
import com.mytallybook.accountbook.auth.service.BootstrapService;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.DatabaseSessionTokenVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = {AccountBookServerApplication.class, AuthMysqlIntegrationTests.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ResourceLock("account-book-test-schema")
class AuthMysqlIntegrationTests {

    private static final String BOOTSTRAP_KEY = "test-only-bootstrap-key-2026";
    private static final String TOKEN_PEPPER =
            "test-only-token-pepper-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");

    private static MysqlTestDatabaseSupport database;

    @Autowired
    private BootstrapService bootstrapService;

    @Autowired
    private AuthService authService;

    @Autowired
    private DatabaseSessionTokenVerifier tokenVerifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        try {
            database = MysqlTestDatabaseSupport.openResetSchema();
            database.migrate();
        } catch (RuntimeException | Error | SQLException failure) {
            closeAfterSetupFailure(failure);
            throw new IllegalStateException("Unable to prepare exclusive MySQL test database", failure);
        }

        registry.add("spring.datasource.url", database::jdbcUrl);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.wechat.app-id", () -> "test-app-id");
        registry.add("app.wechat.app-secret", () -> "test-app-secret");
        registry.add("app.auth.bootstrap-key", () -> BOOTSTRAP_KEY);
        registry.add("app.auth.token-pepper", () -> TOKEN_PEPPER);
        registry.add("app.auth.session-ttl", () -> "30d");
        registry.add("app.auth.token-bytes", () -> "32");
    }

    @AfterAll
    static void cleanTestSchema() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void serializesBootstrapAndEnforcesTheCompleteSessionLifecycle() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<BootstrapAttempt> attempts;
        try {
            Future<BootstrapAttempt> first = executor.submit(
                    () -> bootstrapAfterSignal("owner-code-a", "bootstrap-a", ready, start)
            );
            Future<BootstrapAttempt> second = executor.submit(
                    () -> bootstrapAfterSignal("owner-code-b", "bootstrap-b", ready, start)
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            attempts = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(attempts).filteredOn(BootstrapAttempt::authenticated).hasSize(1);
        assertThat(attempts).filteredOn(attempt -> ErrorCode.ALREADY_INITIALIZED.equals(
                attempt.errorCode()
        )).hasSize(1);

        BootstrapAttempt winner = attempts.stream()
                .filter(BootstrapAttempt::authenticated)
                .findFirst()
                .orElseThrow();
        AuthResult bootstrapResult = winner.result();

        assertThat(count("app_user")).isEqualTo(1);
        assertThat(count("ledger")).isEqualTo(1);
        assertThat(countWhere("ledger_member", "role = 'OWNER'")).isEqualTo(1);
        assertThat(count("category")).isEqualTo(15);
        assertThat(count("fund_account")).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT initialized FROM app_config WHERE id = 1",
                Boolean.class
        )).isTrue();
        assertStoredTokensAreDigestsOnly(List.of(bootstrapResult.token()));

        AuthResult relogin = authService.login(winner.code(), "login-1");
        assertThat(relogin.state()).isEqualTo(AuthState.AUTHENTICATED);
        assertThat(tokenVerifier.verify(bootstrapResult.token())).isEmpty();
        CurrentUser currentUser = tokenVerifier.verify(relogin.token()).orElseThrow();

        authService.logout(relogin.token(), currentUser, "logout-1");
        assertThat(tokenVerifier.verify(relogin.token())).isEmpty();

        AuthResult sessionForStatusChecks = authService.login(winner.code(), "login-2");
        assertThat(tokenVerifier.verify(sessionForStatusChecks.token())).isPresent();

        jdbcTemplate.update(
                "UPDATE app_user SET status = 'DISABLED' WHERE id = ?",
                currentUser.userId()
        );
        assertThat(tokenVerifier.verify(sessionForStatusChecks.token())).isEmpty();

        jdbcTemplate.update(
                "UPDATE app_user SET status = 'ACTIVE' WHERE id = ?",
                currentUser.userId()
        );
        jdbcTemplate.update(
                "UPDATE ledger_member SET status = 'REMOVED' WHERE id = ?",
                currentUser.memberId()
        );
        assertThat(tokenVerifier.verify(sessionForStatusChecks.token())).isEmpty();

        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM audit_log ORDER BY id",
                String.class
        )).contains("SYSTEM_BOOTSTRAP", "AUTH_LOGIN", "AUTH_LOGOUT");
        assertAuditContainsNoSecrets(List.of(
                BOOTSTRAP_KEY,
                bootstrapResult.token(),
                relogin.token(),
                sessionForStatusChecks.token(),
                "owner-code-a",
                "owner-code-b"
        ));
        assertStoredTokensAreDigestsOnly(List.of(
                bootstrapResult.token(),
                relogin.token(),
                sessionForStatusChecks.token()
        ));
    }

    private BootstrapAttempt bootstrapAfterSignal(
            String code,
            String requestId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent bootstrap start signal timed out");
        }
        try {
            return new BootstrapAttempt(
                    code,
                    bootstrapService.bootstrap(code, BOOTSTRAP_KEY, requestId),
                    null
            );
        } catch (BusinessException exception) {
            return new BootstrapAttempt(code, null, exception.errorCode());
        }
    }

    private int count(String tableName) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
    }

    private int countWhere(String tableName, String predicate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE " + predicate,
                Integer.class
        );
    }

    private void assertStoredTokensAreDigestsOnly(List<String> rawTokens) {
        List<String> storedHashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM auth_session",
                String.class
        );
        assertThat(storedHashes).isNotEmpty().allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(storedHashes).doesNotContainAnyElementsOf(rawTokens);
    }

    private void assertAuditContainsNoSecrets(List<String> secrets) {
        List<String> auditRows = jdbcTemplate.queryForList("""
                SELECT CONCAT_WS('|', action, resource_type, request_id, details_json)
                FROM audit_log
                """, String.class);
        assertThat(auditRows).isNotEmpty();
        secrets.forEach(secret -> assertThat(auditRows)
                .allSatisfy(row -> assertThat(row).doesNotContain(secret)));
    }

    private static void closeAfterSetupFailure(Throwable failure) {
        if (database == null) {
            return;
        }
        try {
            database.close();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record BootstrapAttempt(String code, AuthResult result, ErrorCode errorCode) {
        boolean authenticated() {
            return result != null && result.state() == AuthState.AUTHENTICATED;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        Clock fixedIntegrationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        WechatSessionClient stubWechatSessionClient() {
            return code -> new WechatIdentity("openid-" + code, null);
        }
    }
}
