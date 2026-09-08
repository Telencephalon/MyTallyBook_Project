# Task 4 微信登录、会话与唯一所有者初始化 Implementation Plan

> # 历史/已废弃：不可执行
>
> 本计划记录的是 2026-08-30 的实现背景，已经被固定测试库方案替代。下文全部是不可执行的历史摘录，不得复制其中任何数据库生命周期、管理员连接或测试命令；当前安全门禁和唯一可执行入口见 [后端基础模块收敛实施记录](../../11-后端基础模块收敛实施记录.md)。

> **历史说明：** 原“逐任务实现”提示已废弃；不得按本文件的复选框、命令或预期结果执行任何操作。

**历史目标：** 曾计划在现有 Spring Boot 后端中实现微信 `code2Session` 登录、一次性 OWNER 初始化、HMAC 会话、退出、当前用户资料和固定账本查询。

**Architecture:** 控制器只负责 HTTP 合同，`AuthService`/`BootstrapService` 在事务外兑换微信 code 和生成候选 Token，独立事务服务负责数据库写入、行锁和审计。数据访问统一收敛在 `AuthStore`/`JdbcAuthStore`，会话只保存 `HMAC-SHA256` 摘要，认证过滤器通过数据库实时校验用户、成员和账本状态。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring MVC、Spring Security、Spring JDBC、Spring Transaction、Flyway、MySQL 8、JUnit 5、AssertJ、Mockito、MockMvc

**Spec:** `docs/superpowers/specs/2026-08-30-task4-auth-bootstrap-design.md`

## Global Constraints

- 只在 `codex/mvp-development` 隔离 worktree 中修改文件。
- 不部署、不连接生产 `account_book`、不修改京东云现有服务或端口。
- 不使用 Docker、Redis、消息队列、微服务或存储过程。
- 数据访问使用 `JdbcTemplate + 明确 SQL + Spring 事务`。
- 原始 Token、微信 code、AppSecret、`session_key` 和初始化口令不得写入数据库、日志或审计。
- Token 为 32 字节 `SecureRandom`，Base64URL 无填充；数据库只保存 `lowercase_hex(HMAC-SHA256(key=APP_TOKEN_PEPPER, message=rawToken))`。
- 每个用户只保留一个有效会话；成功重新登录撤销旧会话，退出撤销当前会话。
- 固定账本 `id=1`，默认名“共享账本”，最多 10 人。
- 所有接口沿用 Task 3 的 `ApiResponse`、`ApiError`、`requestId` 和稳定业务错误码。
- 所有新增开发文档使用 Markdown。
- 历史测试库生命周期说明已废弃；当前固定测试库的保留、预检和 schema 清理要求仅以 [docs/11](../../11-后端基础模块收敛实施记录.md) 为准。
- 遵循用户的版本控制边界：本计划不执行 `git commit` 或 `git push`；每个任务以测试和 `git diff --check` 作为检查点。

## File Structure

### Authentication configuration and cryptography

- `auth/config/AuthProperties.java`: 初始化口令、pepper、Token 长度和会话时长的强类型配置。
- `auth/config/WechatProperties.java`: 微信 AppID、AppSecret、端点和超时配置。
- `auth/config/AuthModuleConfiguration.java`: 注册配置属性、`Clock`、`SecureRandom` 和微信客户端。
- `auth/session/IssuedSessionToken.java`: 原始 Token、摘要和到期时间的内部值对象。
- `auth/session/SessionTokenService.java`: Token 生成和 HMAC 摘要。

### WeChat boundary

- `auth/wechat/WechatIdentity.java`: 仅保留 `openid`/`unionid`。
- `auth/wechat/WechatSessionClient.java`: 业务层可替换接口。
- `auth/wechat/WechatCode2SessionClient.java`: 微信官方接口实现及错误映射。

### Persistence and domain services

- `auth/store/AuthStore.java`: Task 4 所需的精确数据访问语义和查询投影。
- `auth/store/JdbcAuthStore.java`: 所有明确 SQL、键生成和行映射。
- `auth/service/AuthState.java`: 三种登录状态。
- `auth/service/AuthResult.java`: 登录/初始化返回模型。
- `auth/service/AuthService.java`: 登录和退出编排。
- `auth/service/AuthTransactionService.java`: 登录签发与退出的事务写入和审计。
- `auth/service/BootstrapService.java`: 新微信 code 兑换和候选 Token 生成。
- `auth/service/BootstrapTransactionService.java`: `app_config FOR UPDATE` 唯一初始化事务。

### HTTP and authenticated queries

- `auth/web/AuthController.java` and request/response DTOs: 登录、初始化、退出合同。
- `security/DatabaseSessionTokenVerifier.java`: 数据库会话实时校验。
- `user/UserService.java`, `user/UserController.java` and DTOs: `/users/me` 查询与三态 PATCH。
- `ledger/LedgerService.java`, `ledger/LedgerController.java`, `ledger/LedgerResponse.java`: 固定账本查询。
- `common/error/ErrorCode.java`: Task 4 稳定错误码。

### Tests and documentation

- `auth/session/SessionTokenServiceTests.java`
- `auth/wechat/WechatCode2SessionClientTests.java`
- `auth/store/JdbcAuthStoreTests.java`
- `auth/service/AuthServiceTests.java`
- `auth/service/BootstrapServiceTests.java`
- `security/DatabaseSessionTokenVerifierTests.java`
- `auth/AuthHttpIntegrationTests.java`
- `database/MysqlTestDatabaseSupport.java`
- `database/AuthMysqlIntegrationTests.java`
- `docs/09-微信登录会话与唯一所有者初始化实施记录.md`

---

### Task 1: 强类型配置和 Token 安全组件

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/config/AuthProperties.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/config/WechatProperties.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/config/AuthModuleConfiguration.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/session/IssuedSessionToken.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/session/SessionTokenService.java`
- Modify: `account-book-server/src/main/resources/application.yml`
- Modify: `account-book-server/src/test/resources/application-test.yml`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/session/SessionTokenServiceTests.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/config/AuthPropertiesTests.java`

**Interfaces:**
- Produces: `IssuedSessionToken SessionTokenService.issue()`
- Produces: `String SessionTokenService.hash(String rawToken)`
- Produces: `AuthProperties(String bootstrapKey, String tokenPepper, Duration sessionTtl, int tokenBytes)`
- Produces: `WechatProperties(String appId, String appSecret, URI sessionEndpoint, Duration connectTimeout, Duration readTimeout)`

- [x] **Step 1: Write failing Token tests**

```java
@Test
void issuesThirtyTwoRandomBytesAsBase64UrlAndStoresOnlyHmacDigest() {
    SessionTokenService service = serviceWithFixedRandomBytesAndClock();
    IssuedSessionToken issued = service.issue();

    assertThat(Base64.getUrlDecoder().decode(issued.rawToken())).hasSize(32);
    assertThat(issued.tokenHash()).matches("[0-9a-f]{64}");
    assertThat(issued.expiresAt()).isEqualTo(INSTANT.plus(Duration.ofDays(30)));
    assertThat(issued.tokenHash()).isEqualTo(service.hash(issued.rawToken()));
}
```

Also verify different Token or pepper values produce different digests.

- [x] **Step 2: Run focused tests and verify RED**

```powershell
Set-Location account-book-server
.\mvnw.cmd -Dtest=SessionTokenServiceTests,AuthPropertiesTests test
```

Expected: compilation fails because the new configuration and session classes do not exist.

- [x] **Step 3: Implement validated properties and HMAC service**

```java
public record IssuedSessionToken(String rawToken, String tokenHash, Instant expiresAt) {}

public final class SessionTokenService {
    public IssuedSessionToken issue();
    public String hash(String rawToken);
}
```

`issue()` calls `SecureRandom.nextBytes(new byte[32])`, encodes with `Base64.getUrlEncoder().withoutPadding()`, computes `HmacSHA256` with pepper as `SecretKeySpec`, and uses injected `Clock` for 30-day expiry. Digest bytes use `HexFormat.of().formatHex(...)`.

Add configuration:

```yaml
app:
  wechat:
    app-id: ${WECHAT_APP_ID:}
    app-secret: ${WECHAT_APP_SECRET:}
    session-endpoint: https://api.weixin.qq.com/sns/jscode2session
    connect-timeout: 3s
    read-timeout: 5s
  auth:
    bootstrap-key: ${APP_BOOTSTRAP_KEY:}
    token-pepper: ${APP_TOKEN_PEPPER:}
    session-ttl: 30d
    token-bytes: 32
```

Test profile values must be visibly fake. `bootstrapKey` is blank or 20～256 characters; pepper is at least 32 characters; TTL is positive; token bytes must equal 32.

- [x] **Step 4: Run tests and verify GREEN**

```powershell
.\mvnw.cmd -Dtest=SessionTokenServiceTests,AuthPropertiesTests test
```

Expected: all focused tests pass.

- [x] **Step 5: Checkpoint**

```powershell
git diff --check
git status --short
```

Expected: no secret value is added.

### Task 2: 微信 `code2Session` 客户端与错误映射

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/wechat/WechatIdentity.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/wechat/WechatSessionClient.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/wechat/WechatCode2SessionClient.java`
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/config/AuthModuleConfiguration.java`
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/common/error/ErrorCode.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/wechat/WechatCode2SessionClientTests.java`

**Interfaces:**
- Consumes: `WechatProperties`
- Produces: `WechatIdentity WechatSessionClient.exchange(String code)`
- Produces error codes: `WECHAT_CODE_INVALID`, `WECHAT_LOGIN_BLOCKED`, `WECHAT_SERVICE_UNAVAILABLE`, `WECHAT_SERVICE_TIMEOUT`, `WECHAT_SERVICE_ERROR`

- [x] **Step 1: Write failing client contract tests**

Use `MockRestServiceServer` bound to a dedicated `RestClient.Builder` and assert the request contains `appid`, `secret`, `js_code`, and `grant_type=authorization_code`.

```java
@Test
void returnsOnlyOpenidAndUnionidAndDiscardsSessionKey() {
    server.expect(requestTo(containsString("grant_type=authorization_code")))
            .andRespond(withSuccess("""
                    {"openid":"openid-1","session_key":"must-not-escape","unionid":"union-1"}
                    """, MediaType.APPLICATION_JSON));
    assertThat(client.exchange("fresh-code"))
            .isEqualTo(new WechatIdentity("openid-1", "union-1"));
}
```

Parameterized tests cover `40029 -> 400`, `40226 -> 403`, `-1/45011 -> 503`, unknown error/invalid JSON/missing openid/non-2xx -> 502, and timeout -> 504.

- [x] **Step 2: Run focused test and verify RED**

```powershell
.\mvnw.cmd -Dtest=WechatCode2SessionClientTests test
```

Expected: missing client classes and error codes.

- [x] **Step 3: Implement minimal client**

```java
@FunctionalInterface
public interface WechatSessionClient {
    WechatIdentity exchange(String code);
}

public record WechatIdentity(String openid, String unionid) {}
```

Use a dedicated `RestClient` and `SimpleClientHttpRequestFactory` with configured timeouts. Parse only documented fields; never put `session_key`, request URI, code, AppSecret, or raw response in errors/logs. Map failures with `BusinessException` and the exact `ErrorCode`.

- [x] **Step 4: Run client and Task 3 regression tests**

```powershell
.\mvnw.cmd -Dtest=WechatCode2SessionClientTests,ApiInfrastructureIntegrationTests test
```

Expected: all pass and errors remain structured.

- [x] **Step 5: Checkpoint**

```powershell
git diff --check
rg -n "session_key|APP_SECRET|AppSecret" src/main src/test
```

Expected: `session_key` only occurs in response parsing/test fixtures.

### Task 3: `AuthStore` and explicit JDBC SQL

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/store/AuthStore.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/store/JdbcAuthStore.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/store/JdbcAuthStoreTests.java`

**Interfaces:**
- Produces: config row reads/lock, login projection, initialization inserts, session operations, authenticated principal, profile and ledger projections

- [x] **Step 1: Define interface and write failing SQL tests**

```java
record AppConfigState(boolean initialized, int maxUsers, long version) {}
record LoginMembership(long userId, String userStatus, Long ledgerId,
                       Long memberId, MemberRole role,
                       String memberStatus, String ledgerStatus) {}
record UserProfileView(long userId, String nickname, String avatarUrl,
                       long ledgerId, long memberId, MemberRole role,
                       String displayName) {}
record LedgerView(long id, String name, String currency,
                  String timezone, int maxMembers) {}
```

Required methods:

```java
AppConfigState readAppConfig();
AppConfigState lockAppConfig();
Optional<LoginMembership> findLoginMembership(String openid);
long insertUser(String openid, String unionid, String nickname, Instant now);
void insertLedger(long ownerUserId, int maxMembers, Instant now);
long insertOwnerMembership(long ownerUserId, Instant now);
int insertDefaultCategories();
int insertDefaultFundAccounts();
void markInitialized(long expectedVersion, Instant now);
void revokeAllSessions(long userId, Instant revokedAt);
long insertSession(long userId, String tokenHash, Instant expiresAt, Instant now);
void updateLastLogin(long userId, Instant now);
int revokeSession(String tokenHash, Instant revokedAt);
Optional<CurrentUser> findCurrentUserByTokenHash(String tokenHash, Instant now);
Optional<UserProfileView> findUserProfile(long userId);
void updateUserProfile(long userId, boolean nicknamePresent, String nickname,
                       boolean avatarPresent, String avatarUrl, Instant now);
Optional<LedgerView> findLedger(long ledgerId);
```

Assert `lockAppConfig` uses `FOR UPDATE`, persistence sees only 64-char digest, verifier SQL joins all active statuses and fixed ledger `id=1`, and user input is always bound.

- [x] **Step 2: Run and verify RED**

```powershell
.\mvnw.cmd -Dtest=JdbcAuthStoreTests test
```

Expected: store classes missing.

- [x] **Step 3: Implement `JdbcAuthStore`**

Use `JdbcTemplate`, `GeneratedKeyHolder`, and `Timestamp.from(instant)`. Defaults are exactly 9 expense categories, 6 income categories and 4 approved accounts. Accept `ObjectProvider<JdbcTemplate>` so datasource-free test context loads; methods fail closed if JDBC is absent.

- [x] **Step 4: Run focused and context tests**

```powershell
.\mvnw.cmd -Dtest=JdbcAuthStoreTests,AccountBookServerApplicationTests test
```

Expected: all pass.

- [x] **Step 5: Checkpoint**

```powershell
git diff --check
rg -n "SELECT|INSERT|UPDATE" src/main/java/com/mytallybook/accountbook/auth
```

Expected: SQL only exists in `JdbcAuthStore`.

### Task 4: 登录状态机与唯一初始化事务

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/AuthState.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/AuthResult.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/AuthService.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/AuthTransactionService.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/BootstrapService.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/service/BootstrapTransactionService.java`
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/common/error/ErrorCode.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/service/AuthServiceTests.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/service/BootstrapServiceTests.java`

**Interfaces:**
- Consumes: `WechatSessionClient`, `AuthStore`, `SessionTokenService`, `AuditLogService`
- Produces: `AuthResult AuthService.login(String code, String requestId)`
- Produces: `AuthResult BootstrapService.bootstrap(String code, String bootstrapKey, String requestId)`
- Produces: `void AuthService.logout(String rawToken, CurrentUser currentUser, String requestId)`

- [x] **Step 1: Write failing login state tests**

```java
assertThat(loginWhenNotInitialized().state()).isEqualTo(AuthState.NEED_BOOTSTRAP);
assertThat(loginWithoutActiveMembership().state()).isEqualTo(AuthState.INVITE_REQUIRED);
assertThatThrownBy(this::loginDisabledUser)
        .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.USER_DISABLED));
assertThat(loginActiveMember().state()).isEqualTo(AuthState.AUTHENTICATED);
```

Verify微信兑换发生 before transaction collaborator. `NEED_BOOTSTRAP` and `INVITE_REQUIRED` must never insert user/session.

- [x] **Step 2: Write failing initialization tests**

Cover correct key, wrong key, missing configured key and already initialized. Assert successful order:

```text
lock config -> compare key -> insert user -> insert ledger -> insert OWNER
-> insert 15 categories -> insert 4 accounts -> mark initialized
-> revoke sessions -> insert session -> append SYSTEM_BOOTSTRAP
```

The comparison hashes both strings to fixed SHA-256 byte arrays and uses `MessageDigest.isEqual`.

- [x] **Step 3: Run and verify RED**

```powershell
.\mvnw.cmd -Dtest=AuthServiceTests,BootstrapServiceTests test
```

Expected: missing services/business errors.

- [x] **Step 4: Implement orchestration and transaction services**

```java
public enum AuthState { AUTHENTICATED, NEED_BOOTSTRAP, INVITE_REQUIRED }

public record AuthResult(AuthState state, String token, Instant expiresAt) {
    static AuthResult state(AuthState state);
    static AuthResult authenticated(IssuedSessionToken issued);
}
```

`AuthService.login` and `BootstrapService.bootstrap` remain non-transactional. `AuthTransactionService.authenticate/logout` and `BootstrapTransactionService.bootstrap` use `@Transactional`, so network calls never hold DB connections/locks.

Add exact errors:

```text
BOOTSTRAP_NOT_CONFIGURED 503
BOOTSTRAP_KEY_INVALID    403
ALREADY_INITIALIZED     409
USER_DISABLED           403
```

Login revokes all old sessions, updates `last_login_at`, inserts digest and audits `AUTH_LOGIN`. Bootstrap uses locked `max_users` for `ledger.max_members`, creates all defaults, marks initialized and audits `SYSTEM_BOOTSTRAP` before commit.

- [x] **Step 5: Run and verify GREEN**

```powershell
.\mvnw.cmd -Dtest=AuthServiceTests,BootstrapServiceTests,AuditLogServiceTests test
```

Expected: every state, audit boundary and order test passes.

- [x] **Step 6: Checkpoint**

```powershell
git diff --check
rg -n "bootstrapKey|rawToken|openid|session_key" src/main/java/com/mytallybook/accountbook/auth
```

Expected: no log/audit captures a sensitive value.

### Task 5: 数据库会话认证与当前会话退出

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/security/DatabaseSessionTokenVerifier.java`
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/security/SecurityConfiguration.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/security/DatabaseSessionTokenVerifierTests.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/common/ApiInfrastructureIntegrationTests.java`

**Interfaces:**
- Consumes: `String SessionTokenService.hash(String rawToken)`
- Consumes: `Optional<CurrentUser> AuthStore.findCurrentUserByTokenHash(String tokenHash, Instant now)`
- Produces: production `SessionTokenVerifier`

- [x] **Step 1: Write failing verifier tests**

```java
@Test
void hashesRawTokenBeforeQueryingTheStore() {
    when(tokenService.hash("raw-token")).thenReturn("a".repeat(64));
    when(store.findCurrentUserByTokenHash("a".repeat(64), NOW))
            .thenReturn(Optional.of(CURRENT_USER));
    assertThat(verifier.verify("raw-token")).contains(CURRENT_USER);
    verify(store, never()).findCurrentUserByTokenHash(eq("raw-token"), any());
}
```

Also verify blank input never queries and store exceptions propagate to the existing filter's safe 500 path.

- [x] **Step 2: Run and verify RED**

```powershell
.\mvnw.cmd -Dtest=DatabaseSessionTokenVerifierTests,ApiInfrastructureIntegrationTests test
```

Expected: production verifier missing.

- [x] **Step 3: Implement verifier and bean selection**

Hash raw Bearer Token, query at injected `Clock.instant()`, and return only active rows. Register it as production verifier; retain rejecting fallback only if no database verifier bean exists. Do not update `last_seen_at` in Task 4.

- [x] **Step 4: Run security regression**

```powershell
.\mvnw.cmd -Dtest=DatabaseSessionTokenVerifierTests,ApiInfrastructureIntegrationTests test
```

Expected: invalid token 401 and verifier failure safe 500.

- [x] **Step 5: Checkpoint**

```powershell
git diff --check
```

### Task 6: 安全的 MySQL 临时库生命周期

**Files:**
- Create: `account-book-server/src/test/java/com/mytallybook/accountbook/database/MysqlTestDatabaseSupport.java`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/database/DatabaseMigrationTests.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/database/MysqlTestDatabaseSupportTests.java`

**Interfaces:**
- Produces: `MysqlTestDatabaseSupport.createExclusive()`
- Produces: `MysqlTestDatabaseSupport.migrate()`
- Produces: guarded `MysqlTestDatabaseSupport.close()`

- [x] **Step 1: Write failing lifecycle guard tests**

```java
assertThatThrownBy(() -> validateDatabaseName("account_book"))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> validateDatabaseName("account_book_test_extra"))
        .isInstanceOf(IllegalArgumentException.class);
assertThatCode(() -> validateDatabaseName("account_book_test"))
        .doesNotThrowAnyException();
```

Existence SQL is `SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?`; nonzero count must abort before DDL.

- [x] **Step 2: Run and verify RED**

```powershell
.\mvnw.cmd -Dtest=MysqlTestDatabaseSupportTests test
```

Expected: support class missing.

- [x] **Step 3: Implement guarded lifecycle and repair migration test**

历史方案曾涉及创建或删除测试数据库，现已废弃且不提供可复制命令。当前固定测试库只允许专用账号清理既有 `account_book_test` 的 schema 对象；安全门禁见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

- [x] **Step 4: Run guard and gated migration tests**

```powershell
.\mvnw.cmd -Dtest=MysqlTestDatabaseSupportTests,DatabaseMigrationTests test
```

历史预期已废弃；不得据此创建、迁移或删除测试数据库。当前门禁仅见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

- [x] **Step 5: Checkpoint**

历史自查命令已移除；当前安全检查和结果记录见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

### Task 7: 真实 MySQL 8 初始化并发与会话集成测试

**Files:**
- Create: `account-book-server/src/test/java/com/mytallybook/accountbook/database/AuthMysqlIntegrationTests.java`
- Reuse: `account-book-server/src/test/java/com/mytallybook/accountbook/database/MysqlTestDatabaseSupport.java`

**Interfaces:**
- Consumes production `JdbcAuthStore`, transaction services, `SessionTokenService`, and Flyway V1
- Uses test-only Stub `WechatSessionClient`; never calls real WeChat

- [x] **Step 1: Write gated integration test**

```java
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
```

Start a non-web Spring context against freshly created/migrated `account_book_test`, fake pepper/key, fixed `Clock` and Stub identities. Submit two correct bootstrap calls with different openids using `ExecutorService` and `CountDownLatch`.

Assert exactly one `AUTHENTICATED`, one `ALREADY_INITIALIZED`, and counts `1 user / 1 ledger / 1 OWNER / 15 categories / 4 accounts`; `app_config.initialized=true`; stored Token is only 64 lowercase hex.

- [x] **Step 2: Add lifecycle assertions**

- Re-login owner: old Token fails, new Token succeeds.
- Logout current Token: it fails.
- New session + `app_user.status='DISABLED'`: verifier fails immediately.
- Restore user + `ledger_member.status='REMOVED'`: verifier fails immediately.
- Audit contains `SYSTEM_BOOTSTRAP`, `AUTH_LOGIN`, `AUTH_LOGOUT` and no secret values.

- [x] **Step 3: Run through local-only SSH tunnel**

此历史步骤已废弃，不再提供数据库连接或环境变量命令。当前固定测试库的安全门禁和执行入口见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

- [x] **Step 4: Verify cleanup**

历史验收“测试库不存在”已废弃。当前方案要求固定 `account_book_test` 始终保留，只清理其 schema 对象；详见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

- [x] **Step 5: Checkpoint**

```powershell
git diff --check
git status --short
```

历史预期“测试库不存在”已废弃；当前固定库必须保留，具体门禁仅见 [docs/11](../../11-后端基础模块收敛实施记录.md)。

### Task 8: Auth、当前用户和固定账本 HTTP 接口

**Files:**
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/web/WechatLoginRequest.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/web/BootstrapRequest.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/web/AuthStateResponse.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/web/LogoutResponse.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/auth/web/AuthController.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/user/UserProfileResponse.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/user/UpdateProfileRequest.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/user/UserService.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/user/UserController.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/ledger/LedgerResponse.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/ledger/LedgerService.java`
- Create: `account-book-server/src/main/java/com/mytallybook/accountbook/ledger/LedgerController.java`
- Test: `account-book-server/src/test/java/com/mytallybook/accountbook/auth/AuthHttpIntegrationTests.java`

**Interfaces:**
- Produces: `POST /api/v1/auth/wechat/login`, `/api/v1/auth/bootstrap`, `/api/v1/auth/logout`
- Produces: `GET/PATCH /api/v1/users/me`, `GET /api/v1/ledger`
- Consumes: authenticated `CurrentUser` via `@AuthenticationPrincipal`

- [x] **Step 1: Write failing MockMvc contract tests**

Use a test `@Primary SessionTokenVerifier` and mocked services. Assert exact envelope:

```java
mockMvc.perform(post("/api/v1/auth/wechat/login")
        .contentType(APPLICATION_JSON)
        .content("{\"code\":\"fresh-code\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.code").value("OK"))
    .andExpect(jsonPath("$.data.state").value("NEED_BOOTSTRAP"))
    .andExpect(jsonPath("$.requestId").isNotEmpty());
```

Cover all states, bootstrap success/errors, field validation, protected 401, logout, user query/PATCH and ledger query.

- [x] **Step 2: Write failing tri-state PATCH tests**

```text
{}                       -> 400 VALIDATION_FAILED
{"nickname":"家庭成员"} -> update nickname only
{"avatarUrl":null}      -> clear avatar only
```

Reject `nickname:null`, blank nickname, non-HTTPS avatar and over-512 avatar. `UpdateProfileRequest` tracks setter presence independently from value.

- [x] **Step 3: Run and verify RED**

```powershell
.\mvnw.cmd -Dtest=AuthHttpIntegrationTests test
```

Expected: endpoint classes missing.

- [x] **Step 4: Implement controllers and services**

Every controller returns `ApiResponse.success(data, RequestIdFilter.getRequestId(request))`. Convert expiry to UTC `OffsetDateTime`. Logout extracts the already validated Bearer value without logging it.

`UserService.update` is transactional, updates only present fields and audits booleans `nicknameChanged`/`avatarChanged`. `LedgerService` queries authenticated `ledgerId`; missing rows produce `RESOURCE_NOT_FOUND`.

- [x] **Step 5: Run HTTP and local suites**

```powershell
.\mvnw.cmd -Dtest=AuthHttpIntegrationTests,ApiInfrastructureIntegrationTests test
.\mvnw.cmd test
```

Expected: all non-MySQL tests pass; DB-gated tests skip without variables.

- [x] **Step 6: Checkpoint**

```powershell
git diff --check
```

### Task 9: 全量回归、敏感信息扫描与 Markdown 实施记录

**Files:**
- Modify: `docs/07-微信共享记账小程序最终开发执行计划.md`
- Create: `docs/09-微信登录会话与唯一所有者初始化实施记录.md`
- Modify if contract requires: `README.md`

**Interfaces:**
- Documents final API, environment variable names, evidence, deferred scope and next task

- [x] **Step 1: Run full backend verification**

```powershell
Set-Location account-book-server
.\mvnw.cmd clean verify
```

Expected: zero failures/errors; DB tests pass when explicitly enabled or are clearly skipped.

- [x] **Step 2: Run remaining project checks**

```powershell
Set-Location ..\account-book-miniapp
npm run typecheck
npm audit --omit=dev
Set-Location ..
powershell -ExecutionPolicy Bypass -File deploy/nginx/tests/Test-NginxTemplates.ps1
git diff --check
git status --short
```

Run a targeted scan for the actual credentials previously disclosed in chat without printing matching content. Report only counts and file paths; expected count zero.

- [x] **Step 3: Update Markdown records**

`docs/09-微信登录会话与唯一所有者初始化实施记录.md` records endpoints/states, environment variable names without values, HMAC/transaction design, guarded DB cleanup evidence, exact test counts, production untouched, and deferred Task 5/Task 6 scope.

Mark Task 4 complete in `docs/07-微信共享记账小程序最终开发执行计划.md` only after required checks pass.

- [x] **Step 4: Final self-review**

```powershell
rg -n "TODO|TBD|FIXME|待确认" docs/09-微信登录会话与唯一所有者初始化实施记录.md
git diff --check
```

Expected: no placeholder, secret, generated output or unintended server change.

- [x] **Step 5: Present implementation checkpoint**

历史错误结论已废弃：不得把 `account_book_test` 是否存在作为完成条件。当前固定测试库必须保留，只清理其 schema 对象；唯一有效门禁见 [docs/11](../../11-后端基础模块收敛实施记录.md)。
