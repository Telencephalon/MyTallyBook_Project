# Task 6 Invites and Members Implementation Plan

> 2026-09-06 当前进度：Task1–5 本地实现与代码复核已完成；资料页保存状态残留问题经专项 RED/GREEN 和独立复审关闭。最新完整离线结果：后端 270 通过/19 数据库入口跳过，小程序 160 通过/两套类型检查通过。用户在电脑重启后另行授权恢复原 SSH 与原后端，健康检查 HTTP 200、UP；仅恢复原版本，未打包或替换 JAR。真实 MySQL、新版后端与新功能人工验收仍为下方独立未执行门禁，详见[实施记录](../../16-邀请与成员模块实施记录.md)。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Implement the approved invitation/member vertical slice, with shared write guards, single-use acceptance, role changes, leave/removal and ownership transfer.

**Architecture:** Existing Spring Boot/JdbcTemplate transactions serialize identity writes using app_config first, then current ledger/member reads. A four-page TypeScript miniapp feature uses existing HTTP/session infrastructure. Database schema stays immutable; actual MySQL execution is a separate gate.

**Tech Stack:** Java 21, Spring Boot 4.1.0, JdbcTemplate, MySQL 8, JUnit/Mockito/MockMvc, native WeChat TypeScript, Vitest.

**Spec:** [Approved detailed specification](../specs/2026-09-05-task6-invites-members-design.md)

## Global Constraints

- Work only in `D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project-worktrees/codex-mvp-development`, branch `codex/mvp-development`; preserve dirty baseline. No commit, push, stash, reset or source cleanup.
- Fixed ledger id 1; at most `min(10, app_config.max_users, ledger.max_members)` ACTIVE users with ACTIVE memberships. OWNER/ADMIN/MEMBER only.
- Original OWNER becomes MEMBER on transfer. OWNER demotes ADMIN before removal. Non-owner self-leave is LEFT; removal is REMOVED. Rejoin as MEMBER, preserve memberId, never restore DISABLED/DELETED users.
- Active member accepting invitation yields ALREADY_MEMBER without consuming invite or changing sessions. Losing management authority physically revokes unused, unexpired invitations in the same transaction.
- All identity/member write transactions lock app_config first. Current reads revalidate roles, capacity and unique valid OWNER. No network in database transactions. Logout retains the security-reducing exception in spec 6.1.
- Invite 32 random bytes, 43-character Base64URL without padding; HMAC-SHA256 with existing Pepper over `invite:v1:` plus raw token. Defaults 24 hours, range 1..168. List/filters/accept use one effective-state contract; expiry clock read after lock wait.
- V1/V2 unchanged. No credentials read/decrypted, key rotation, database connection/cleanup/migration, SSH/cloud actions, service restart, JAR overwrite or package/clean commands. `mvnw -o test` only; explicitly empty DB_TEST_URL/USERNAME/PASSWORD/RESET_ALLOWED in each test process.
- The real MySQL suite may be written and compiled now, but execution requires separate approval and dedicated test credentials. Never substitute dev credentials. No software/dependency upgrades.
- Backend working directory is `<worktree>/account-book-server`; JDK `D:/Work/Config/JDK/JDK/jdk21`. Miniapp is `<worktree>/account-book-miniapp`; Node/npm `D:/Work/Tools/Node24/nodejs`.
- TDD: real RED before production changes, targeted GREEN while iterating, full offline regression at each task finish. No speculative test hooks in production. Preserve checkpoint/review files because there are no commits to recover them from.

## Execution and evidence

The user chose implementation explicitly; execute in this session without another workflow-choice prompt. Each task brief includes its full requirements plus these constraints. Store briefs/reports and task-scoped before/after diffs in `.superpowers/sdd/2026-09-05-task6-invites-members-implementation/`. Since the baseline is uncommitted, snapshot only relevant source/test files before each task and create review diffs against those snapshots, not against HEAD alone. Parent performs spec/quality review through a fresh reviewer after each task.

Safe backend command (same process, never print environment values):

```powershell
$env:JAVA_HOME = 'D:/Work/Config/JDK/JDK/jdk21'
$env:Path = $env:JAVA_HOME + '/bin;' + $env:Path
$env:DB_TEST_URL = ''
$env:DB_TEST_USERNAME = ''
$env:DB_TEST_PASSWORD = ''
$env:DB_TEST_RESET_ALLOWED = ''
./mvnw.cmd -o '-Dtest=AuthTransactionServiceTests' test
```

Miniapp command: `& 'D:/Work/Tools/Node24/nodejs/npm.cmd' test`; then `npm.cmd run typecheck`. Existing dependencies are installed. Do not run multiple Maven writers concurrently against target/.

## Task 1: Shared write guard and fresh login/profile identity

**实施回执：** 已完成并独立审查通过。定向修复回归 27 项通过；完整后端 139 项，0 失败/错误、10 项数据库门禁跳过。源码未提交，真实 MySQL 并发结论仍待门禁。

**Files (package prefix `com/mytallybook/accountbook`):**
- Create: `account-book-server/src/main/java/.../ledger/LedgerWriteGuard.java`.
- Create: `.../member/store/MemberStore.java`, `JdbcMemberStore.java` for current-read ledger/member/user access.
- Modify: `.../auth/store/AuthStore.java`, `JdbcAuthStore.java`; `.../auth/service/AuthService.java`, `AuthTransactionService.java`, `BootstrapTransactionService.java`; `.../user/UserService.java`; `.../common/error/ErrorCode.java`.
- Tests: new `ledger/LedgerWriteGuardTests.java`, `member/store/JdbcMemberStoreTests.java`; existing `auth/service/AuthTransactionServiceTests.java`, `AuthServiceTests.java`, `BootstrapServiceTests.java`, `auth/store/JdbcAuthStoreTests.java`, `auth/AuthHttpIntegrationTests.java`, `database/AuthMysqlIntegrationTests.java` as needed for constructor/signature fixtures.

**Interfaces:**
Create these nested MemberStore records and reads; use current project Supplier<JdbcTemplate> injection pattern so ordinary no-DB test profile still starts:

```java
record LedgerState(long id, long ownerUserId, int maxMembers, String status, long version) {}
record MemberState(long memberId, long userId, String userStatus, MemberRole role,
                   String status, String nickname, String displayName, Instant joinedAt) {}
record UserState(long id, String status) {}
Optional<LedgerState> lockLedger(); // WHERE id=1 FOR UPDATE
List<MemberState> lockMembers();   // ledger_id=1, ORDER BY member id FOR UPDATE
Optional<UserState> lockUserByOpenid(String openid);
Optional<LedgerState> readLedger();
List<MemberState> readMembers();
```

Guard public operations: `LockedLedger lock()` calls authStore.lockAppConfig, validates initialized/maxUsers, locks fixed ledger then member rows, checks single active OWNER equals ownerUserId. `LockedLedger` contains config/ledger/members with `int maxMembers()` and `MemberState requireActor(CurrentUser)`; the latter uses the current list rather than supplied role and rejects wrong ledger/member identity. `assertOwnerInvariant(LedgerState,List<MemberState>)` is usable before committing bootstrap/transfer. Require a real active transaction for guard entry; unit tests use a TransactionTemplate with a lightweight test transaction manager, not a production bypass.

AuthStore adds `Optional<LoginMembership> lockLoginMembership(long userId)` using a current read. AuthTransactionService changes to `authenticate(long userId, IssuedSessionToken issued, String requestId)`. Acquire guard, re-read account/member, reject DISABLED, return INVITE_REQUIRED for deleted/missing/inactive member; no new session. Active path retains rotation/update/audit. Bootstrap validates the newly-created owner state before commit. Logout calls config lock directly, not full invariant guard. Profile update locks config then revalidates current user/ledger/member before writing so a removed actor cannot edit using stale CurrentUser.

- [x] Write regression RED first using current authenticate signature before changing it: arrange stale ACTIVE membership but current read REMOVED; assert result INVITE_REQUIRED and no insert/revoke. Add ordering, invalid OWNER, removed profile, and logout exception cases.

```java
assertThat(result.state()).isEqualTo(AuthState.INVITE_REQUIRED);
verify(authStore, never()).insertSession(anyLong(), anyString(), any(), any());
// Distinct fixtures: no OWNER, two OWNERs, owner_user_id mismatch, valid one OWNER.
// Logout with an existing malformed config row still revokes the current token once.
```

- [x] Run focused existing auth tests and new guard tests; record expected failing assertion, not a missing import.
- [x] Implement guard/current-read records, methods and minimal call-site changes; adapt fixtures without erasing old assertions. Add SYSTEM_NOT_INITIALIZED and LEDGER_STATE_CONFLICT safe errors.

```java
LockedLedger locked = guard.lock();
var membership = authStore.lockLoginMembership(userId);
if (membership.isEmpty() || !active(membership.get())) {
    return AuthResult.state(AuthState.INVITE_REQUIRED);
}
// Disabled branch precedes inactive result; no writes until all checks pass.
```

- [x] Run targeted tests then `./mvnw.cmd -o test` with cleared DB variables. Report counts/skips and any pre-existing Mockito/JDK agent warnings honestly.
- [x] Self-review and task review; preserve files uncommitted. Produce reusable exact signatures in task report for Task 2/3.

## Task 2: Complete invitation backend

**实施回执：** 已完成并独立审查通过。定向 94 项通过；完整后端 204 项，0 失败/错误、10 项数据库门禁跳过。一次非行为格式整理后 27 项通过并复审关闭，真实数据库验证仍未执行。

**Files:** Create production `invite/InviteController.java`, `InviteAcceptController.java`, `InviteService.java`, `InviteTransactionService.java`, `InviteTokenService.java`, DTO files and `invite/store/InviteStore.java`, `JdbcInviteStore.java`; extend member stores for insert/reactivate; modify common error enum/handler and audit sensitive-key set. Tests in `invite/InviteServiceTests.java`, `InviteTransactionServiceTests.java`, `InviteTokenServiceTests.java`, `InviteHttpIntegrationTests.java`, `invite/store/JdbcInviteStoreTests.java`, and existing audit/infrastructure tests.

**Consumes:** Task 1 guard/MemberStore; AuthStore insertUser/revokeAllSessions/insertSession/updateLastLogin; existing WechatSessionClient/SessionTokenService/AuthResult/ApiResponse/CurrentUser.

**Produces:** Controller/service contracts below, shared effective-state policy and `revokeCreatedBy(long userId, CurrentUser actor, String reason, String requestId)` callable inside an existing guarded transaction for Task 3. Method locks relevant invitations then reads its own Clock; no external lock-before timestamp argument. Returns revoked IDs; row updates and one safe audit per invite are transactional. No second independent transaction or lock bypass. Reasons are ROLE_DEMOTION/MEMBER_LEAVE/MEMBER_REMOVE/OWNERSHIP_TRANSFER.

```java
CreatedInvite create(CurrentUser actor, Integer expiresInHours, String requestId);
InvitePage list(CurrentUser actor, int page, int pageSize, String status);
RevokedInvite revoke(CurrentUser actor, long inviteId, String requestId);
AuthResult accept(String code, String inviteToken, String requestId);
// CreatedInvite(id, token, expiresAt, status)
// InviteView(id, createdBy, createdByName, createdAt, expiresAt, status, usedBy, usedAt)
// InvitePage(items, page, pageSize, total); RevokedInvite(id,status)
```

Route contracts exactly spec 4.1; OWNER/ADMIN only create/list/revoke, anonymous accept with real wx code. Use request DTO validation with null/blank/integer bounds; unknown privileged input role/ledgerId/createdBy must not influence writes. Page arithmetic checked against overflow; safe positive JS IDs. List ordered created_at DESC,id DESC, no raw/hash. In one read-only REPEATABLE READ transaction use same clock instant for total/items and effective-state filtering. SQL bound parameters only.

- [x] RED HTTP request to POST /invites as authorized OWNER currently lacks feature; expect successful response envelope and verify request ID/safe fields. Add MEMBER forbidden and malformed input before implementation. Crypto vectors use dummy Pepper only.

```java
mockMvc.perform(post("/api/v1/invites").header("Authorization", "Bearer owner-fixture")
    .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInHours\":24}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
mockMvc.perform(post("/api/v1/invites").header("Authorization", "Bearer owner-fixture")
    .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInHours\":1.5}"))
    .andExpect(status().isBadRequest()); // do not silently coerce a fraction to integer
// Listing assertions: token/tokenHash absent, expiry at NOW => EXPIRED.
```

- [x] Run RED, then implement crypto and service/transaction/store APIs. Invite creation clock after guard; accept exchanges wx outside transaction, then locks guard/user/invite, reads clock after invite lock, rejects states in spec order, checks locked capacity, inserts or reactivates MEMBER with same id, revokes all old sessions, inserts valid candidate, atomically fills USED/used_by/used_at, audits last.

```java
Instant decisionNow = clock.instant(); // AFTER acquiring invitation row
String state = effectiveStatus(row, creatorIsManager, decisionNow);
// USED/REVOKED/EXPIRED stored values win; ACTIVE expired => EXPIRED;
// otherwise creator invalid => REVOKED; otherwise ACTIVE.
// ALREADY_MEMBER returns before looking up/consuming invite or issuing DB session.
```

- [x] Test all effective statuses, exact expiry/lock delay, disabled/deleted user, active no-op, rejoin role/history, capacity, audit whitelist, domain-separated token, repeated revoke and all failure no-write branches. Keep mocks at DB/network boundaries; SQL binding tests assert real emitted SQL/values and no raw persistence.
- [x] Add safe common handler mapping Spring lock failures to 409 CONFLICT (not arbitrary SQL errors); sanitize inviteToken/inviteCode/tokenHash recursively, with safe dummy tests.
- [x] Focused GREEN then full offline Maven test, self-review and task review. Report exact shared revoke/member-store interfaces for next task.

## Task 3: Member management and transfer backend

**实施回执：** 已完成并独立审查通过。定向 68 项通过；完整后端 266 项，0 失败/错误、10 项数据库门禁跳过。成员移除与自退统一关闭异常残留未过期邀请；邀请码共享接口未变，真实 MySQL 仍待门禁。

**Files:** Create `member/MemberController.java`, `MemberService.java`, DTO response/request files; extend `member/store/MemberStore.java`, `JdbcMemberStore.java`. Tests `member/MemberServiceTests.java`, `MemberHttpIntegrationTests.java`, store tests. Do not edit invitation contracts after review without reporting changed dependency.

**Consumes:** Task 1 guard locked member list, Task 2 revokeCreatedBy operation, AuthStore.revokeAllSessions and audit.

**Produces:**

```java
MemberList list(CurrentUser actor); // items, activeCount, maxMembers, ownerUserId
MemberView changeRole(CurrentUser actor, long memberId, MemberRole role, String requestId);
RemovedMember remove(CurrentUser actor, long memberId, String requestId);
OwnershipTransfer transfer(CurrentUser actor, long memberId, String requestId);
// MemberView(memberId,userId,nickname,displayName,role,joinedAt)
// RemovedMember(memberId,status); OwnershipTransfer(ownerUserId,previousOwnerUserId)
```

Store mutations return/check affected counts: update role with expected previous role/status; remove with expected role/status; transfer ledger owner with expected owner/version. Members are already locked in ascending id; repeat current actor/target checks in transaction, no stale role trust.

- [x] RED HTTP GET /members and PUT role route with literal expected member fields; unauthorized matrix OWNER/ADMIN/MEMBER, caller/target pairs. Add service test that a stale OWNER cannot transfer after demotion and no mutations occur.

```java
assertThatThrownBy(() -> service.transfer(staleOwner, otherMemberId, "r-transfer"))
    .isInstanceOfSatisfying(BusinessException.class,
        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
// Use actual BusinessException accessor from source if named differently.
```

- [x] Run RED, implement list in single read-only RR snapshot with active user/member count and validated capacity. Implement both PUT/PATCH via same handler; only OWNER modifies other ADMIN/MEMBER, no OWNER assignment or self-role edit, same role no-op.
- [x] Implement remove/self-leave, all old-session revoke, automatic creator-invite revocation. OWNER self-leave -> OWNER_TRANSFER_REQUIRED; OWNER removing ADMIN -> ADMIN_DEMOTION_REQUIRED, ADMIN targeting ADMIN/OWNER -> ACCESS_DENIED.
- [x] Implement transfer as one transaction: revalidate source/target, old OWNER→MEMBER, target→OWNER, ledger owner+version, revoke old manager's unused invites, verify resulting unique OWNER, audit.

```java
store.changeRole(oldOwner.memberId(), MemberRole.OWNER, MemberRole.MEMBER);
store.changeRole(target.memberId(), target.role(), MemberRole.OWNER);
store.transferOwner(oldOwner.userId(), target.userId(), locked.ledger().version());
// Check each affected count == 1, validate fresh locked OWNER set, audit last.
```

- [x] GREEN tests cover MEMBER removal only, ADMIN demotion then removal, leave vs logout, re-promotion does not revive invites, role no-op no extra audit, transfer to self/inactive invalid, no fields leaked. Full offline Maven regression, self-review, task review.

## Task 4: Miniapp invitation/member vertical slice

**实施回执：** 四页面和入口已完成并通过分项独立审查。分项修复后 136 项/15 文件、首次集成修复后 156 项/16 文件；最终发现的资料页保存状态残留问题，经用户继续授权完成专项 24 项 RED/GREEN 和独立复审，最新完整 160 项/16 文件及两套类型检查通过。SessionStore 仅增加内存 revision，持久化字段不变；尚未做新功能微信渲染、分享和真实后端联调。

**Files:** Extend `account-book-miniapp/miniprogram/types/api.ts`, `runtime.ts`, `flows/session-flow.ts`, `app.json`, home and login page files. Create `services/invite.ts`, `services/member.ts`, `flows/invite-flow.ts`, `flows/member-flow.ts`; four page directories `invite-create`, `invite-accept`, `member-list`, `member-edit`, each `index.ts/.wxml/.wxss/.json`. Tests `tests/services/invite-member-api.test.ts`, `tests/flows/invite-member-flow.test.ts`, `tests/pages/invite-member-pages.test.ts` (split pages tests into invitation/member files if clarity requires).

**Consumes:** Existing HttpClient, SessionStore, SessionFlow, getWechatCode, safe AppError. Backend Task 2/3 JSON field names above, HTTP 200 success; PUT role change. Existing displayName null fallback preserved.

**Integration preflight clarification:** Extend `services/http.ts` minimally and its HTTP boundary tests: capture the sent Bearer token, and only clear session for an authenticated401 if that token still matches the current session. A delayed old-identity401 must not clear a newly established session; same-token401 and anonymous accept behavior remain unchanged. SessionFlow separately rejects stale context success responses by token/refresh generation. No automatic replay or new persisted fields.

**Produces:** Runtime adds `invites: InviteFlow` and `members: MemberFlow`; existing `flow`/`session` unchanged. SessionFlow exposes `establishAuthenticatedSession(auth: AuthStateData): Promise<void>` for common success processing, no raw-token persistent fields beyond existing version/token/expiresAt. InviteFlow methods create/list/revoke/accept; MemberFlow list/changeRole/remove/transfer; mutations refresh user+ledger context, self-leave clears session only on success. Plain services mirror approved paths.

- [x] RED actual page/flow tests with Page/wx platform stubs, network boundary only. Test inviting MEMBER has no management action; direct accept page doesn't auto-login; it obtains fresh code only on click; failure preserves pre-existing session; success saves returned session/context.

```ts
await page.onAccept()
expect(session.getToken()).toBe('existing-test-session') // fixture returns INVITE_USED
expect(platform.wxLogin).toHaveBeenCalledTimes(1)
// Destroy create page => raw invitation gone; hiding for share chooser => retained.
```

- [x] Run focused RED. Implement API types/services, flows and runtime. Preserve authenticated:false on accept so invalid old Bearer isn't sent and 4xx doesn't erase valid session. No automatic retry, no API mocking in shipped code, no storing raw invitation/code in Storage/global singleton.
- [x] Implement four pages using existing visual styles; creation 24 default integer 1..168, history pagination/filter/revoke, one-time code copy/share only. Raw invite held only on page instance, clear on identity loss/unload; share path only approved route+encoded token. Accept manually pasted/query token, normalize once, strict 43-char case-sensitive Base64URL, no clipboard auto-read. Duplicate-click guard and busy reset in finally.
- [x] Member list all active, capacity/role/display names; member edit role/removal/transfer controls per role, two-step confirmations with explicit effect. OWNER transfer warns becoming MEMBER. Non-owner self-leave distinct from logout. Recheck latest context when pages shown; outdated privileges fail safely server-side.
- [x] Home entry member management for all; invitations only managers. Login INVITE_REQUIRED offers accept page navigation without discarding a share token. Trial/release HTTPS restrictions remain unchanged; no bypass for phone testing.
- [x] Tests verify actual Page methods, copy/share/unload/confirm/navigation, strict API paths/bodies, role controls, ALREADY_MEMBER no consumption/session replacement, rejoin success, failure cleanup, no sensitive persistent data. Include a pending create response resolving after unload/identity loss: it must not repopulate the cleared invitation. A per-page lifecycle generation/identity check can discard the stale result without production test hooks. Run full npm test and both typechecks, self-review and task review.

## Task 5: Gated MySQL concurrency suite and release checkpoint

**实施回执：** 九组 RC/RR 测试源码、普通/预编译 Statement 的真实 JDBC 观察器及清理安全保护已通过独立审查。修复前普通 Statement 离线用例 6 个断言失败，修复后定向 12 项通过；10:50:15 后端最新完整复跑 289 项、0 失败/错误、19 跳过。资料页残留问题及本地整体复核已关闭；真实数据库上下文、锁与物理回滚仍未执行，不能以离线通过代替数据库门禁。

**Files:** Create `account-book-server/src/test/java/com/mytallybook/accountbook/database/InviteMemberMysqlIntegrationTests.java`; add `@ResourceLock("account-book-test-schema")` to existing AuthMysqlIntegrationTests if absent. Create `docs/16-邀请与成员模块实施记录.md`; update README, docs07/10/15/spec status truthfully. No migrations or launcher changes.

**Consumes:** Completed production Task 1..4; MysqlTestDatabaseSupport fixed guard; existing AuthMysqlIntegrationTests dynamic datasource setup and cleanup. Extend test-only fixtures, not production lifecycle hooks.

The shared JUnit ResourceLock coordinates suites in one run only; it is not a server-wide advisory lock. Before eventual authorized execution, require an exclusive test window with no second test process. Do not claim existing MysqlTestDatabaseSupport implements GET_LOCK; it does not. No new cross-process lock mechanism in this task.

- [x] Write integration assertions for spec 8 nine MySQL scenarios using two independent connections/transactions, CountDownLatch barriers and Future timeouts. Both RC and RR connection-local transaction settings; no global isolation changes. Ensure test class conditional on DB_TEST_URL and resource lock, explicit reset support before any DB action.

```java
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ResourceLock("account-book-test-schema")
class InviteMemberMysqlIntegrationTests {
    // Use real proxied services with database support and stub only WeChat exchange.
    // Two successful candidates competing for seat 10 => exactly one AUTHENTICATED.
    // Query database: 10 active, one USED invite, loser has no member/session residue.
}
```

- [x] Compile/run offline Maven tests with DB variables absent: gated suite must report skipped, not pass. Do not claim RED/GREEN for unexecuted DB-only scenarios; record pending real execution explicitly. Reuse offline service tests as implemented behavior's RED/GREEN evidence, not proof of row-lock behavior.
- [x] Check scenario source for old token still invalid after removed user rejoins, two transfer winner semantics, invitation expiry during lock wait, audit-failure rollback, profile/logout implicit FK cycles using bounded waits. Each scenario uses actual business services and asserts tables/state, not only absence of exceptions. This checkbox records source coverage review only; actual MySQL execution remains a separate unexecuted gate.
- [x] Full offline backend/miniapp regressions, types, diff/JSON checks; independent final integration review. Do not run clean/package or touch active JAR. Record test counts, skipped actual-DB cases and warnings separately.
- [x] Update implementation/manual acceptance docs: user-confirmed login/bootstrap/home/name/logout/restoration, implemented code vs pending MySQL vs pending new JAR/manual feature/true multi-device evidence. Request separate database gate authorization only when code/review is ready; no re-request of approved business rules.

### Unexecuted external gates (not covered by local completion)

2026-09-06 实际门禁曾获准并启动，但用户在执行途中明确暂缓容量竞争/第 11 人拒绝场景，随后已停止整批测试进程。没有取得完整本次报告，不标记其余场景为通过；后续须按更新的验证范围重新确认执行，保留成员上限业务规则。

最新授权已于同日取得：用户允许独占窗口中仅清理 `account_book_test`，明确只选择 group2～group9 的 16 项 RC/RR 验证；全部通过后才备份旧 JAR、更新并启动本地后端。14:55:40 新报告确认 16/0/0/0，Maven 退出码 0，主代理复核八组各两项。旧 JAR 已备份，但停止旧后端未能完成，未打包/替换；保留原健康应用，保存并暂停，Task7/8 不开始。容量场景仍暂缓，不将完整 18 项门禁改记为通过。

- [x] Obtain explicit permission and exclusive test window; verify dedicated test identity; clean only account_book_test and execute the approved groups2..9 real MySQL RC/RR gates:16 passed,0 failures/errors/skips. No development credentials or database substitutions.
- [ ] Deferred by user: group1 capacity competition/member11 rejection, two RC/RR executions. Preserve business rules and do not claim full18 acceptance.
- [x] Update local backend after passing the approved gate. On 2026-09-06 the separately approved Start-Dev -UpdateBackend flow verified a durable backup, packaged current source, and restarted only the backend; exit 0, new Java31920, HTTP200/UP, original SSH18576 and encrypted configuration preserved. Ordinary startup then reused both services. This supersedes the earlier failed stop; no real-DB gate was rerun.
- [ ] Verify new invitation/member features in WeChat, record Network/Storage/Console evidence without secrets, and complete real multi-user/device acceptance in a compliant HTTPS environment.

## Spec coverage and controller preflight

| Specification | Tasks |
|---|---|
| 1–2 scope and confirmed rules | 1–5 constraints; 2/3 behavior; 4 UI |
| 3 component responsibilities | 1 guard/stores; 2 invite; 3 member; 4 client |
| 4 API/validation/error/DTO contracts | 2/3 backend; 4 clients |
| 5 crypto and audit | 2; 3 revocation events; 4 no persistence |
| 6 locks/current read/races | 1–3 production; 5 actual DB tests |
| 7 interactions | 4 |
| 8 verification and external gates | each task offline RED/GREEN; 5 DB/manual gate |
| 9 implementation progression | this plan and persisted task reports |

Do not mark all tasks complete when only compilation passes. Real MySQL gate and new-feature manual verification remain separate pending stages until executed with authority.
