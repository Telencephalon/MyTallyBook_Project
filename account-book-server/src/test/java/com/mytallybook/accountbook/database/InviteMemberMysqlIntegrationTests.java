package com.mytallybook.accountbook.database;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.service.*;
import com.mytallybook.accountbook.auth.wechat.WechatIdentity;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.invite.*;
import com.mytallybook.accountbook.member.MemberService;
import com.mytallybook.accountbook.security.*;
import com.mytallybook.accountbook.user.UpdateProfileRequest;
import com.mytallybook.accountbook.user.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.mytallybook.accountbook.database.ObservedMysqlDataSource.Phase.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Destructive, exclusive, opt-in test-schema suite. No outer test transactions:
 * login/accept must reach WeChat before their real transactional service proxies.
 * Each specification group runs on actual RC and RR business connections.
 */
@SpringBootTest(classes = {AccountBookServerApplication.class, InviteMemberMysqlIntegrationTests.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ResourceLock("account-book-test-schema")
@Execution(ExecutionMode.SAME_THREAD)
class InviteMemberMysqlIntegrationTests {
    private static final String BOOTSTRAP_KEY = "test-only-bootstrap-key-2026";
    private static final Instant START = Instant.parse("2026-09-05T02:00:00Z");
    private static MysqlTestDatabaseSupport database;
    private static volatile boolean safeToClean = true;
    private static final ThreadLocal<String> WORKER = new ThreadLocal<>();

    enum Isolation {
        RC(Connection.TRANSACTION_READ_COMMITTED), RR(Connection.TRANSACTION_REPEATABLE_READ);
        final int jdbc; Isolation(int jdbc) { this.jdbc = jdbc; }
    }

    @Autowired BootstrapService bootstrap;
    @Autowired AuthService auth;
    @Autowired AuthTransactionService authTransactions;
    @Autowired InviteService invites;
    @Autowired InviteTransactionService inviteTransactions;
    @Autowired MemberService members;
    @Autowired UserService users;
    @Autowired AuditLogService audit;
    @Autowired DatabaseSessionTokenVerifier verifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObservedMysqlDataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    private CurrentUser owner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            database = MysqlTestDatabaseSupport.openResetSchema();
            database.migrate();
        } catch (RuntimeException | Error | SQLException failure) {
            if (database != null) {
                try { database.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw new IllegalStateException("Unable to prepare exclusive test schema", failure);
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

    @AfterAll static void cleanSchemaOnlyAfterWorkersHaveStopped() {
        assertThat(safeToClean).as("Workers must be confirmed stopped before schema cleanup").isTrue();
        if (database != null) database.close();
    }

    // Catches capacity checks based on stale counts, premature invite use, or loser residue.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group1_lastSeatAndEleventhRejection(Isolation isolation) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            fresh(isolation);
            for (int i = 0; i < 8; i++) join("seed-" + i);
            CreatedInvite a = create(owner), b = create(owner);
            String winner = reverse ? "candidate-b" : "candidate-a";
            String loser = reverse ? "candidate-a" : "candidate-b";
            CreatedInvite winning = reverse ? b : a, losing = reverse ? a : b;
            Pair result = ordered(configAfter(), () -> accept(winner, winning, "seat-win"),
                    () -> accept(loser, losing, "seat-lose"));
            AuthResult accepted = result.first.success(AuthResult.class);
            result.second.error(ErrorCode.MEMBER_LIMIT_REACHED);
            assertThat(activeCount()).isEqualTo(10);
            assertAccepted(winning, accepted, winner, "seat-win");
            assertThat(number("SELECT COUNT(*) FROM ledger_invite WHERE id IN (?,?) AND status='USED'", a.id(), b.id())).isEqualTo(1);
            assertUnused(losing);
            assertNoIdentity(loser, "seat-lose");
            attempt(() -> accept("eleventh", losing, "eleventh")).error(ErrorCode.MEMBER_LIMIT_REACHED);
            assertNoIdentity("eleventh", "eleventh");
            assertUnused(losing);
        }
    }

    // Catches a non-atomic consume or missing lock/current-state recheck on a shared invite.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group2_sameInviteOnlyOneUse(Isolation isolation) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            fresh(isolation);
            CreatedInvite invitation = create(owner);
            String first = reverse ? "second" : "first", second = reverse ? "first" : "second";
            Pair result = ordered(configAfter(), () -> accept(first, invitation, "same-win"),
                    () -> accept(second, invitation, "same-lose"));
            assertAccepted(invitation, result.first.success(AuthResult.class), first, "same-win");
            result.second.error(ErrorCode.INVITE_USED);
            assertNoIdentity(second, "same-lose");
            assertThat(activeCount()).isEqualTo(2);
            assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='INVITE_ACCEPT' AND resource_id=?", invitation.id())).isEqualTo(1);
        }
    }

    // Catches trusting the outside-transaction ACTIVE login snapshot, and token resurrection.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group3_staleLoginAndRemovalBothCommitOrders(Isolation isolation) throws Exception {
        fresh(isolation);
        Session person = join("person");
        long before = number("SELECT COUNT(*) FROM auth_session WHERE user_id=?", person.actor.userId());
        Gate snapshot = new Gate();
        dataSource.observer = event -> {
            if ("A".equals(WORKER.get()) && event.phase() == ROWS_CLOSED
                    && event.sql().contains("where u.openid = ?") && event.sql().contains("member_status")) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                snapshot.pause(); // JdbcTemplate already mapped the real ACTIVE row before ResultSet.close().
            }
        };
        try (Workers workers = new Workers(snapshot)) {
            Future<Attempt> login = workers.submit("A", () -> auth.login("person", "stale-login"));
            snapshot.awaitReached();
            members.remove(owner, person.actor.memberId(), "remove-before-login");
            snapshot.release();
            assertThat(workers.get(login).success(AuthResult.class).state()).isEqualTo(AuthState.INVITE_REQUIRED);
        } finally { dataSource.observer = ObservedMysqlDataSource.NONE; }
        assertThat(number("SELECT COUNT(*) FROM auth_session WHERE user_id=?", person.actor.userId())).isEqualTo(before);
        assertThat(audits("stale-login")).isZero();
        Session rejoined = join("person");
        assertThat(rejoined.actor.memberId()).isEqualTo(person.actor.memberId());
        assertThat(verifier.verify(person.token)).isEmpty();
        Pair loginFirst = ordered(configAfter(), () -> auth.login("person", "login-first"),
                () -> members.remove(owner, person.actor.memberId(), "remove-after-login"));
        AuthResult newest = loginFirst.first.success(AuthResult.class);
        loginFirst.second.success();
        join("person");
        assertThat(verifier.verify(person.token)).isEmpty();
        assertThat(verifier.verify(rejoined.token)).isEmpty();
        assertThat(verifier.verify(newest.token())).isEmpty();
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE request_id='login-first' AND action='AUTH_LOGIN'")).isEqualTo(1);
    }

    // Catches restoring ADMIN privileges or replacing the membership/history on rejoin.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group4_removedAndLeftAdminsRejoinWithoutOldPrivilegesOrSessions(Isolation isolation) {
        for (boolean selfLeave : List.of(false, true)) {
            fresh(isolation);
            Session admin = admin("admin");
            AuthResult laterLogin = auth.login("admin", "fixture-admin-login");
            jdbc.update("UPDATE app_user SET nickname='Preserved nickname' WHERE id=?", admin.actor.userId());
            jdbc.update("UPDATE ledger_member SET display_name='Preserved alias' WHERE id=?", admin.actor.memberId());
            Instant joined = instant("SELECT joined_at FROM ledger_member WHERE id=?", admin.actor.memberId());
            CreatedInvite oldInvite = create(admin.actor);
            attempt(() -> members.remove(owner, admin.actor.memberId(), "direct-admin-remove"))
                    .error(ErrorCode.ADMIN_DEMOTION_REQUIRED);
            if (selfLeave) members.remove(admin.actor, admin.actor.memberId(), "leave-admin");
            else {
                members.changeRole(owner, admin.actor.memberId(), MemberRole.MEMBER, "demote-admin");
                members.remove(owner, admin.actor.memberId(), "remove-admin");
            }
            assertThat(value("SELECT status FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo(selfLeave ? "LEFT" : "REMOVED");
            assertThat(value("SELECT status FROM ledger_invite WHERE id=?", oldInvite.id())).isEqualTo("REVOKED");
            clock.advance(Duration.ofSeconds(2));
            Session again = join("admin");
            assertThat(again.actor.memberId()).isEqualTo(admin.actor.memberId());
            assertThat(again.actor.role()).isEqualTo(MemberRole.MEMBER);
            assertThat(value("SELECT nickname FROM app_user WHERE id=?", admin.actor.userId())).isEqualTo("Preserved nickname");
            assertThat(value("SELECT display_name FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo("Preserved alias");
            assertThat(value("SELECT status FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForMap("SELECT removed_at FROM ledger_member WHERE id=?", admin.actor.memberId()).get("removed_at")).isNull();
            assertThat(instant("SELECT joined_at FROM ledger_member WHERE id=?", admin.actor.memberId())).isAfter(joined);
            assertThat(verifier.verify(admin.token)).isEmpty();
            assertThat(verifier.verify(laterLogin.token())).isEmpty();
            assertThat(number("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", admin.actor.userId())).isEqualTo(1);
        }
    }

    // Catches a stale OWNER actor authorizing a second transfer or losing the only OWNER.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group5_transfersAndTargetRemoval(Isolation isolation) throws Exception {
        for (boolean reverse : List.of(false, true)) {
            fresh(isolation);
            Session a = join("a"), b = join("b");
            Session first = reverse ? b : a, second = reverse ? a : b;
            long version = number("SELECT version FROM ledger WHERE id=1");
            Pair transfers = ordered(configAfter(), () -> members.transfer(owner, first.actor.memberId(), "transfer-win"),
                    () -> members.transfer(owner, second.actor.memberId(), "transfer-lose"));
            transfers.first.success(); transfers.second.error(ErrorCode.ACCESS_DENIED);
            assertOwner(first.actor.userId(), version + 1);
            assertThat(value("SELECT role FROM ledger_member WHERE id=?", owner.memberId())).isEqualTo("MEMBER");
            assertThat(audits("transfer-win")).isEqualTo(1);
            assertThat(audits("transfer-lose")).isZero();
        }
        for (boolean transferFirst : List.of(false, true)) {
            fresh(isolation);
            Session target = join("target"), admin = admin("remover");
            long version = number("SELECT version FROM ledger WHERE id=1");
            Work transfer = () -> members.transfer(owner, target.actor.memberId(), "target-transfer");
            Work remove = () -> members.remove(admin.actor, target.actor.memberId(), "target-remove");
            Pair race = ordered(configAfter(), transferFirst ? transfer : remove, transferFirst ? remove : transfer);
            race.first.success();
            race.second.error(transferFirst ? ErrorCode.ACCESS_DENIED : ErrorCode.MEMBER_STATE_CHANGED);
            assertOwner(transferFirst ? target.actor.userId() : owner.userId(), version + (transferFirst ? 1 : 0));
            assertThat(value("SELECT status FROM ledger_member WHERE id=?", target.actor.memberId())).isEqualTo(transferFirst ? "ACTIVE" : "REMOVED");
        }
    }

    // Catches stale manager authorization and invitations surviving normal authority loss.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group6_demotionAgainstCreateAndAcceptBothOrders(Isolation isolation) throws Exception {
        for (boolean managementFirst : List.of(false, true)) {
            fresh(isolation);
            Session admin = admin("admin");
            Work create = () -> invites.create(admin.actor, 24, "create-race");
            Work demote = () -> members.changeRole(owner, admin.actor.memberId(), MemberRole.MEMBER, "demote-race");
            Pair creation = ordered(configAfter(), managementFirst ? create : demote, managementFirst ? demote : create);
            creation.first.success();
            if (managementFirst) {
                creation.second.success();
                CreatedInvite created = creation.first.success(CreatedInvite.class);
                assertThat(value("SELECT status FROM ledger_invite WHERE id=?", created.id())).isEqualTo("REVOKED");
                assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='INVITE_REVOKE' AND resource_id=?", created.id())).isEqualTo(1);
            } else {
                creation.second.error(ErrorCode.ACCESS_DENIED);
                assertThat(audits("create-race")).isZero();
            }

            fresh(isolation);
            Session acceptingAdmin = admin("admin");
            CreatedInvite invitation = create(acceptingAdmin.actor);
            Work accept = () -> accept("new-person", invitation, "accept-race");
            Work downgrade = () -> members.changeRole(owner, acceptingAdmin.actor.memberId(), MemberRole.MEMBER, "downgrade");
            Pair acceptance = ordered(configAfter(), managementFirst ? accept : downgrade, managementFirst ? downgrade : accept);
            acceptance.first.success();
            if (managementFirst) {
                acceptance.second.success();
                assertAccepted(invitation, acceptance.first.success(AuthResult.class), "new-person", "accept-race");
            } else {
                acceptance.second.error(ErrorCode.INVITE_REVOKED);
                assertNoIdentity("new-person", "accept-race");
                assertThat(value("SELECT status FROM ledger_invite WHERE id=?", invitation.id())).isEqualTo("REVOKED");
            }
        }
    }

    // Catches the old user/session -> audit FK lock order and missing post-lock identity checks.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group7_profileAndLogoutAgainstMembershipWrites(Isolation isolation) throws Exception {
        for (boolean transfer : List.of(false, true)) {
            for (boolean profileFirst : List.of(false, true)) {
                fresh(isolation);
                Session person = join("profile-person");
                long version = number("SELECT version FROM ledger WHERE id=1");
                UpdateProfileRequest update = new UpdateProfileRequest();
                update.setNickname("Changed under locks");
                Work profile = () -> users.update(person.actor, update, "profile-race");
                Work membership = transfer
                        ? () -> members.transfer(owner, person.actor.memberId(), "membership-race")
                        : () -> members.remove(owner, person.actor.memberId(), "membership-race");
                Predicate<ObservedMysqlDataSource.Event> boundary = profileFirst
                        ? event -> event.phase() == AFTER && event.sql().startsWith("update app_user set nickname")
                        : configAfter();
                Pair result = ordered(boundary, profileFirst ? profile : membership, profileFirst ? membership : profile);
                result.first.success();
                if (!profileFirst && !transfer) result.second.error(ErrorCode.RESOURCE_NOT_FOUND);
                else result.second.success();
                boolean updated = profileFirst || transfer;
                assertThat(value("SELECT nickname FROM app_user WHERE id=?", person.actor.userId()))
                        .isEqualTo(updated ? "Changed under locks" : "微信用户");
                assertThat(audits("profile-race")).isEqualTo(updated ? 1 : 0);
                assertThat(number("SELECT COUNT(*) FROM audit_log WHERE request_id='membership-race' AND action=?",
                        transfer ? "OWNERSHIP_TRANSFER" : "MEMBER_REMOVE")).isEqualTo(1);
                if (transfer) {
                    assertOwner(person.actor.userId(), version + 1);
                    assertThat(verifier.verify(person.token).orElseThrow().role()).isEqualTo(MemberRole.OWNER);
                } else {
                    assertThat(value("SELECT status FROM ledger_member WHERE id=?", person.actor.memberId())).isEqualTo("REMOVED");
                    assertThat(verifier.verify(person.token)).isEmpty();
                }
            }
        }
        for (boolean logoutFirst : List.of(false, true)) {
            fresh(isolation);
            Session person = join("logout-person");
            Work logout = () -> { auth.logout(person.token, person.actor, "logout-race"); return null; };
            Work remove = () -> members.remove(owner, person.actor.memberId(), "logout-remove");
            Predicate<ObservedMysqlDataSource.Event> boundary = logoutFirst
                    ? event -> event.phase() == AFTER && event.sql().startsWith("update auth_session") && event.sql().contains("where token_hash")
                    : configAfter();
            Pair result = ordered(boundary, logoutFirst ? logout : remove, logoutFirst ? remove : logout);
            result.first.success(); result.second.success();
            assertThat(verifier.verify(person.token)).isEmpty();
            assertThat(number("SELECT COUNT(*) FROM auth_session WHERE user_id=? AND revoked_at IS NULL", person.actor.userId())).isZero();
            assertThat(value("SELECT status FROM ledger_member WHERE id=?", person.actor.memberId())).isEqualTo("REMOVED");
            assertThat(audits("logout-race")).isEqualTo(logoutFirst ? 1 : 0);
            assertThat(number("SELECT COUNT(*) FROM audit_log WHERE request_id='logout-remove' AND action='MEMBER_REMOVE'")).isEqualTo(1);
        }
    }

    // Catches partial commits after real invite UPDATE and after successful real audit INSERT.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group8_physicalRollbackAfterBusinessWriteAndAuditInsert(Isolation isolation) {
        for (boolean auditFailure : List.of(false, true)) {
            fresh(isolation);
            CreatedInvite invitation = create(owner);
            Map<String, List<Map<String, Object>>> before = snapshot();
            injectFailure(auditFailure ? auditInsert("INVITE_ACCEPT", "fault-accept") : inviteUse(),
                    () -> accept("new-rollback-user", invitation, "fault-accept"));
            assertThat(snapshot()).isEqualTo(before);
            assertNoIdentity("new-rollback-user", "fault-accept");
            assertUnused(invitation);

            fresh(isolation);
            Session admin = admin("returning-admin");
            members.remove(admin.actor, admin.actor.memberId(), "fixture-leave");
            // Controlled corrupt leftover session: rejoin must revoke it, rollback must restore its timestamp.
            jdbc.update("UPDATE auth_session SET revoked_at=NULL WHERE user_id=?", admin.actor.userId());
            CreatedInvite rejoin = create(owner);
            Map<String, List<Map<String, Object>>> rejoinBefore = snapshot();
            clock.advance(Duration.ofSeconds(2));
            injectFailure(auditFailure ? auditInsert("INVITE_ACCEPT", "fault-rejoin") : inviteUse(),
                    () -> accept("returning-admin", rejoin, "fault-rejoin"));
            assertThat(snapshot()).isEqualTo(rejoinBefore);
            assertThat(value("SELECT role FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo("ADMIN");
            assertThat(value("SELECT status FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo("LEFT");
            assertThat(verifier.verify(admin.token)).isEmpty();
        }
        for (boolean transfer : List.of(false, true)) {
            fresh(isolation);
            Session admin = admin("change-target");
            CreatedInvite affected = create(transfer ? owner : admin.actor);
            long version = number("SELECT version FROM ledger WHERE id=1");
            Map<String, List<Map<String, Object>>> before = snapshot();
            String action = transfer ? "OWNERSHIP_TRANSFER" : "MEMBER_ROLE_CHANGE";
            injectFailure(auditInsert(action, "fault-change"), transfer
                    ? () -> members.transfer(owner, admin.actor.memberId(), "fault-change")
                    : () -> members.changeRole(owner, admin.actor.memberId(), MemberRole.MEMBER, "fault-change"));
            assertThat(snapshot()).isEqualTo(before);
            assertUnused(affected);
            assertThat(audits("fault-change")).isZero(); // Includes already-inserted automatic INVITE_REVOKE audits.
            assertOwner(owner.userId(), version);
            assertThat(value("SELECT role FROM ledger_member WHERE id=?", admin.actor.memberId())).isEqualTo("ADMIN");
        }
    }

    // Catches expiry captured before either lock wait and the resurrection of revoked invitations.
    @ParameterizedTest @EnumSource(Isolation.class)
    void group9_expiryAfterConfigAndInviteWaitAndNoRepromotionRevival(Isolation isolation) throws Exception {
        for (boolean configLock : List.of(false, true)) {
            fresh(isolation);
            CreatedInvite invitation = invites.create(owner, 1, "expiry-create");
            Gate cleanupGate = new Gate();
            CountDownLatch arrived = new CountDownLatch(1), acquired = new CountDownLatch(1);
            dataSource.observer = event -> {
                boolean matching = configLock ? ObservedMysqlDataSource.isConfigLock(event.sql())
                        : event.sql().startsWith("select id, ledger_id, created_by") && event.sql().contains("and token_hash = ? for update");
                if ("B".equals(WORKER.get()) && matching) {
                    if (event.phase() == BEFORE) arrived.countDown();
                    if (event.phase() == AFTER) acquired.countDown();
                }
            };
            try (Connection blocker = dataSource.getConnection()) {
                assertThat(blocker.getTransactionIsolation()).isEqualTo(isolation.jdbc);
                blocker.setAutoCommit(false);
                try (Statement statement = blocker.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (ResultSet held = statement.executeQuery(configLock
                            ? "SELECT id FROM app_config WHERE id=1 FOR UPDATE"
                            : "SELECT id FROM ledger_invite WHERE id=" + invitation.id() + " FOR UPDATE")) {
                        assertThat(held.next()).isTrue();
                    }
                }
                try (Workers workers = new Workers(cleanupGate)) {
                    Future<Attempt> result = workers.submit("B", () -> accept("expired-candidate", invitation, "expiry-accept"));
                    try {
                        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
                        assertThat(acquired.await(200, TimeUnit.MILLISECONDS)).isFalse();
                        clock.set(invitation.expiresAt()); // Equality is expired; candidate session remains live for 30d.
                    } finally { blocker.rollback(); }
                    workers.get(result).error(ErrorCode.INVITE_EXPIRED);
                    assertThat(acquired.getCount()).isZero();
                } finally { blocker.rollback(); }
            } finally { cleanupGate.release(); dataSource.observer = ObservedMysqlDataSource.NONE; }
            assertUnused(invitation); // No expiry write is required on a rejected acceptance.
            assertNoIdentity("expired-candidate", "expiry-accept");
        }
        fresh(isolation);
        Session admin = admin("repromoted-admin");
        CreatedInvite old = create(admin.actor);
        members.changeRole(owner, admin.actor.memberId(), MemberRole.MEMBER, "normal-demotion");
        assertThat(value("SELECT status FROM ledger_invite WHERE id=?", old.id())).isEqualTo("REVOKED");
        members.changeRole(owner, admin.actor.memberId(), MemberRole.ADMIN, "repromotion");
        attempt(() -> accept("revoked-candidate", old, "revoked-accept")).error(ErrorCode.INVITE_REVOKED);
        assertThat(value("SELECT status FROM ledger_invite WHERE id=?", old.id())).isEqualTo("REVOKED");
        assertThat(invites.list(owner, 1, 50, "REVOKED").items()).anySatisfy(item -> assertThat(item.id()).isEqualTo(old.id()));
        assertNoIdentity("revoked-candidate", "revoked-accept");
    }

    private static Predicate<ObservedMysqlDataSource.Event> inviteUse() {
        return event -> event.phase() == AFTER && event.sql().startsWith("update ledger_invite set status = 'used'");
    }
    private static Predicate<ObservedMysqlDataSource.Event> auditInsert(String action, String request) {
        return event -> event.phase() == AFTER && event.sql().startsWith("insert into audit_log")
                && action.equals(event.parameters().get(3)) && request.equals(event.parameters().get(6));
    }
    private void injectFailure(Predicate<ObservedMysqlDataSource.Event> boundary, Work work) {
        java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();
        dataSource.observer = event -> {
            if ("FAULT".equals(WORKER.get()) && boundary.test(event)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(event.connection().getAutoCommit()).isFalse();
                if (event.sql().startsWith("insert into audit_log")) {
                    // Observe the inserted row on the same physical transaction before throwing.
                    try (PreparedStatement statement = event.connection().prepareStatement(
                            "SELECT COUNT(*) FROM audit_log WHERE action=? AND request_id=?")) {
                        statement.setString(1, (String) event.parameters().get(3));
                        statement.setString(2, (String) event.parameters().get(6));
                        try (ResultSet rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1);
                        }
                    }
                }
                reached.set(true);
                throw new InjectedFailure();
            }
        };
        WORKER.set("FAULT");
        try {
            assertThatThrownBy(work::run).isInstanceOf(InjectedFailure.class);
            assertThat(reached.get()).as("Failure occurs only after actual matching SQL completed").isTrue();
        } finally { WORKER.remove(); dataSource.observer = ObservedMysqlDataSource.NONE; }
    }
    private static final class InjectedFailure extends RuntimeException {}
    private Map<String, List<Map<String, Object>>> snapshot() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : List.of("app_config", "app_user", "ledger", "ledger_member", "ledger_invite", "auth_session", "audit_log")) {
            state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return state;
    }

    private void fresh(Isolation isolation) {
        assertThat(safeToClean).as("No cleanup after an unconfirmed worker shutdown").isTrue();
        dataSource.observer = ObservedMysqlDataSource.NONE;
        dataSource.isolation = isolation.jdbc;
        clock.set(START);
        assertThat(jdbc.getDataSource()).isSameAs(dataSource);
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        assertThat(((JpaTransactionManager) transactionManager).getDataSource()).isSameAs(dataSource);
        for (Object bean : List.of(authTransactions, inviteTransactions, members, users, audit)) {
            assertThat(AopUtils.isAopProxy(bean)).as("Real Spring transactional proxy").isTrue();
        }
        assertThatThrownBy(() -> audit.append(new AuditLogService.AuditEvent(1L, 1L, "TEST", "TEST", 1L, "mandatory", Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inviteTransactions.revokeCreatedBy(1, new CurrentUser(1,1,1,MemberRole.OWNER), "ROLE_DEMOTION", "mandatory"))
                .isInstanceOf(IllegalTransactionStateException.class);
        // Committed fixture cleanup, in FK order; never disable foreign-key enforcement.
        for (String table : List.of("audit_log", "book_entry", "ledger_invite", "auth_session", "category", "fund_account", "ledger_member", "ledger", "app_user")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("UPDATE app_config SET initialized=FALSE, max_users=10, version=0 WHERE id=1");
        int before = dataSource.verifiedWriteConnections.get();
        AuthResult boot = bootstrap.bootstrap("owner", BOOTSTRAP_KEY, "fixture-bootstrap");
        owner = verifier.verify(boot.token()).orElseThrow();
        assertThat(dataSource.verifiedWriteConnections.get()).isGreaterThan(before);
    }

    private Session join(String code) {
        AuthResult result = accept(code, create(owner), "fixture-join");
        return new Session(verifier.verify(result.token()).orElseThrow(), result.token());
    }
    private Session admin(String code) {
        Session joined = join(code);
        members.changeRole(owner, joined.actor.memberId(), MemberRole.ADMIN, "fixture-admin");
        return new Session(verifier.verify(joined.token).orElseThrow(), joined.token);
    }
    private CreatedInvite create(CurrentUser actor) { return invites.create(actor, 24, "fixture-create"); }
    private AuthResult accept(String code, CreatedInvite invitation, String request) { return invites.accept(code, invitation.token(), request); }
    private long activeCount() { return number("SELECT COUNT(*) FROM ledger_member m JOIN app_user u ON u.id=m.user_id WHERE m.status='ACTIVE' AND u.status='ACTIVE'"); }
    private long number(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private String value(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    private Instant instant(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Timestamp.class, args)).toInstant(); }
    private long audits(String request) { return number("SELECT COUNT(*) FROM audit_log WHERE request_id=?", request); }
    private void assertUnused(CreatedInvite invitation) {
        assertThat(jdbc.queryForMap("SELECT status, used_by, used_at FROM ledger_invite WHERE id=?", invitation.id()))
                .containsEntry("status", "ACTIVE").containsEntry("used_by", null).containsEntry("used_at", null);
    }
    private void assertNoIdentity(String code, String request) {
        assertThat(number("SELECT COUNT(*) FROM app_user WHERE openid=?", "openid-" + code)).isZero();
        assertThat(number("SELECT COUNT(*) FROM ledger_member m JOIN app_user u ON u.id=m.user_id WHERE u.openid=?", "openid-" + code)).isZero();
        assertThat(number("SELECT COUNT(*) FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE u.openid=?", "openid-" + code)).isZero();
        assertThat(audits(request)).isZero();
    }
    private void assertAccepted(CreatedInvite invitation, AuthResult result, String code, String request) {
        assertThat(result.state()).isEqualTo(AuthState.AUTHENTICATED);
        CurrentUser current = verifier.verify(result.token()).orElseThrow();
        assertThat(current.userId()).isEqualTo(number("SELECT id FROM app_user WHERE openid=?", "openid-" + code));
        assertThat(value("SELECT status FROM ledger_invite WHERE id=?", invitation.id())).isEqualTo("USED");
        assertThat(number("SELECT used_by FROM ledger_invite WHERE id=?", invitation.id())).isEqualTo(current.userId());
        assertThat(instant("SELECT used_at FROM ledger_invite WHERE id=?", invitation.id()))
                .isEqualTo(instant("SELECT joined_at FROM ledger_member WHERE id=?", current.memberId()));
        assertThat(instant("SELECT used_at FROM ledger_invite WHERE id=?", invitation.id()))
                .isEqualTo(instant("SELECT created_at FROM auth_session WHERE user_id=? AND revoked_at IS NULL", current.userId()));
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='INVITE_ACCEPT' AND resource_id=? AND user_id=? AND request_id=?", invitation.id(), current.userId(), request)).isEqualTo(1);
    }
    private void assertOwner(long userId, long version) {
        assertThat(number("SELECT COUNT(*) FROM ledger_member m JOIN app_user u ON u.id=m.user_id WHERE m.role='OWNER' AND m.status='ACTIVE' AND u.status='ACTIVE'")).isEqualTo(1);
        assertThat(number("SELECT user_id FROM ledger_member WHERE role='OWNER' AND status='ACTIVE'")).isEqualTo(userId);
        assertThat(number("SELECT owner_user_id FROM ledger WHERE id=1")).isEqualTo(userId);
        assertThat(number("SELECT version FROM ledger WHERE id=1")).isEqualTo(version);
    }

    private static Predicate<ObservedMysqlDataSource.Event> configAfter() {
        return event -> event.phase() == AFTER && ObservedMysqlDataSource.isConfigLock(event.sql());
    }
    /** A pauses after real SQL; B arrives immediately before its real config lock SQL. */
    private Pair ordered(Predicate<ObservedMysqlDataSource.Event> firstBoundary, Work first, Work second) throws Exception {
        Gate held = new Gate();
        CountDownLatch secondArrived = new CountDownLatch(1), secondAcquired = new CountDownLatch(1);
        AtomicReference<Connection> firstConnection = new AtomicReference<>(), secondConnection = new AtomicReference<>();
        dataSource.observer = event -> {
            if ("A".equals(WORKER.get()) && firstBoundary.test(event)) {
                firstConnection.set(event.connection()); held.pause();
            }
            if ("B".equals(WORKER.get()) && ObservedMysqlDataSource.isConfigLock(event.sql())) {
                secondConnection.set(event.connection());
                if (event.phase() == BEFORE) secondArrived.countDown();
                if (event.phase() == AFTER) secondAcquired.countDown();
            }
        };
        try (Workers workers = new Workers(held)) {
            Future<Attempt> a = workers.submit("A", first);
            held.awaitReached();
            Future<Attempt> b = workers.submit("B", second);
            assertThat(secondArrived.await(5, TimeUnit.SECONDS)).as("B reached its real lock SQL boundary").isTrue();
            assertThat(secondAcquired.await(200, TimeUnit.MILLISECONDS)).as("B cannot acquire A's held lock").isFalse();
            assertThat(firstConnection.get()).isNotSameAs(secondConnection.get());
            held.release();
            Pair result = new Pair(workers.get(a), workers.get(b));
            assertThat(secondAcquired.getCount()).isZero();
            return result;
        } finally { held.release(); dataSource.observer = ObservedMysqlDataSource.NONE; }
    }

    @FunctionalInterface interface Work { Object run() throws Exception; }
    private static Attempt attempt(Work work) {
        try { return new Attempt(work.run(), null); }
        catch (BusinessException failure) { return new Attempt(null, failure.errorCode()); }
        catch (Exception failure) { throw new IllegalStateException("Unexpected business execution failure", failure); }
    }
    private record Attempt(Object value, ErrorCode error) {
        void success() { assertThat(error).isNull(); }
        <T> T success(Class<T> type) { success(); assertThat(value).isInstanceOf(type); return type.cast(value); }
        void error(ErrorCode expected) { assertThat(error).isEqualTo(expected); assertThat(value).isNull(); }
    }
    private record Pair(Attempt first, Attempt second) {}
    private record Session(CurrentUser actor, String token) {}
    private static final class Gate {
        private final CountDownLatch reached = new CountDownLatch(1), released = new CountDownLatch(1);
        void pause() throws InterruptedException {
            reached.countDown();
            if (!released.await(6, TimeUnit.SECONDS)) throw new IllegalStateException("Test SQL gate timed out");
        }
        void awaitReached() throws InterruptedException { assertThat(reached.await(5, TimeUnit.SECONDS)).as("Real SQL boundary reached").isTrue(); }
        void release() { released.countDown(); }
    }
    private static final class Workers implements AutoCloseable {
        private final ExecutorService pool = Executors.newFixedThreadPool(2);
        private final List<Future<?>> futures = new ArrayList<>();
        private final Gate gate;
        Workers(Gate gate) { this.gate = gate; }
        Future<Attempt> submit(String name, Work work) {
            Future<Attempt> future = pool.submit(() -> {
                WORKER.set(name);
                try { return attempt(work); } finally { WORKER.remove(); }
            });
            futures.add(future); return future;
        }
        Attempt get(Future<Attempt> future) throws Exception { return future.get(20, TimeUnit.SECONDS); }
        @Override public void close() throws InterruptedException {
            gate.release();
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            pool.shutdownNow();
            boolean stopped;
            try { stopped = pool.awaitTermination(20, TimeUnit.SECONDS); }
            catch (InterruptedException failure) {
                safeToClean = false; MysqlTestDatabaseSupport.forbidFurtherCleanup();
                Thread.currentThread().interrupt(); throw failure;
            }
            if (!stopped) { safeToClean = false; MysqlTestDatabaseSupport.forbidFurtherCleanup(); }
            assertThat(stopped).as("Workers confirmed stopped; schema cleanup forbidden otherwise").isTrue();
        }
    }
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(START);
        void set(Instant instant) { time.set(instant); }
        void advance(Duration duration) { time.updateAndGet(now -> now.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only"); return this; }
        @Override public Instant instant() { return time.get(); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean ObservedMysqlDataSource dataSource() { return new ObservedMysqlDataSource(Objects.requireNonNull(database)); }
        @Bean @Primary MutableClock integrationClock() { return new MutableClock(); }
        @Bean @Primary WechatSessionClient integrationWechat() {
            return code -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("WeChat must be outside DB transactions").isFalse();
                return new WechatIdentity("openid-" + code, null);
            };
        }
    }
}
