# Task 7–9 Bookkeeping Implementation Plan

> 2026-09-08 执行目录更新：用户明确要求后续只在主目录 `D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project` 开发，不再使用或新建隔离 worktree。此指令覆盖下文历史 worktree 限制；旧目录只保留归档，不删除。Task 7/8 已完成，当前仅恢复 Task 9，实施记录沿用 `docs/18-收支记账与首页统计实施记录.md`。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver usable category/account administration, income/expense bookkeeping, and monthly home/statistics in the existing authenticated miniapp.

**Architecture:** Three sequential vertical slices use JDBC stores, transaction-scoped services and thin HTTP controllers. Shared ledger access and exact money validation are introduced with Task 7 and consumed by Tasks 8–9. Native TypeScript pages use the existing session guard and HTTP envelope; database aggregation remains authoritative.

**Tech Stack:** Java 21, Spring Boot 4.1.0, JDBC, MySQL 8, native WeChat TypeScript, JUnit/Mockito, Vitest/TypeScript. No new dependency.

**Spec:** `docs/superpowers/specs/2026-09-06-task7-9-bookkeeping-design.md` (user approved 2026-09-06). Read the complete spec; this plan does not weaken its edge cases.

## Global Constraints

- 一个固定共享账本，`ledger_id=1`，币种 CNY，账本时区 Asia/Shanghai。
- 全体有效成员可查询分类/账户/账单/统计及新增账单。
- OWNER/ADMIN 管理分类、账户，并可修改/删除全部账单；MEMBER 仅可修改/删除自己创建的账单。权限由服务端最新成员状态核验。
- 分类及账户从未被账单引用时可物理删除；存在引用（包括软删除账单）则拒绝删除并提示停用。
- 金额以十进制字符串传输和返回，返回值固定两位小数；Java/SQL 全程 BigDecimal/DECIMAL，不用浮点数进行货币计算。
- 本规格使用现有表，不修改已执行的 V1/V2，不自行运行 V3、repair、清库或重新初始化。
- 当前密钥和全部现有配置不变。只改 `D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project-worktrees/codex-mvp-development`；不提交、不推送、不清理用户改动。不要修改主 checkout。
- 每项采用测试先行、后端/小程序定向与完整离线回归、TypeScript 检查及独立代码复核。真实 MySQL 专用测试仅在用户明确允许独占清理 account_book_test 后执行，不能借用开发库凭据；不把跳过计为通过。容量竞争验证仍按原指令暂缓。
- Workers do not spawn subagents. One implementation worker at a time; parent coordinates review. No package/clean/install/start/stop/network/database writes during implementation. Use `mvnw.cmd -o test` only. Leave the active JAR intact.
- Before test children, remove process-local DB_TEST_URL/DB_TEST_USERNAME/DB_TEST_PASSWORD/DB_TEST_RESET_ALLOWED and inherited APP_/WECHAT_/DB_/SPRING_ plus JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS/MAVEN_OPTS/MAVEN_ARGS. Never print their contents. Set JAVA_HOME to `D:/Work/Config/JDK/JDK/jdk21`.
- Paths below are relative to the worktree. `server` denotes `account-book-server/src/main/java/com/mytallybook/accountbook`; `test` its parallel `src/test/java/com/mytallybook/accountbook`; `mp` denotes `account-book-miniapp/miniprogram`. These aliases are documentation, not additional directories.

## File and interface map

- Shared backend: `server/ledger/LedgerReadGuard.java` validates a consistent read snapshot using AuthStore/MemberStore/LedgerWriteGuard; `server/common/validation/BookkeepingValidation.java` owns safe IDs, exact decimal/date/name/enum/version validation. `server/common/validation/StrictRequest.java` prevents silently accepting immutable/unknown JSON fields in new DTOs, using the project's Jackson version without changing global old request behavior.
- Task 7: separate category and account domain/store/controller files; shared dictionary API flow and four native pages. Task 8 consumes category/account stores or JDBC lookup in its own store, never controller-to-controller calls. Task 9 reads the same schema, not service list pagination.
- Each slice adds dedicated TypeScript types (`types/catalog.ts`, `types/entry.ts`, `types/statistics.ts`) instead of continually growing `types/api.ts`; service clients `services/catalog.ts`, `services/entry.ts`, `services/statistics.ts`; flows only when coordinating operations; runtime wires them sequentially.
- Read page guards are shared in `mp/utils/page-guard.ts` if exact duplication emerges: captures active generation and session revision. Avoid refactoring existing Task 6 pages. All new pages must protect both read and write completions, including modal callbacks.
- The new page names have four files each: `index.ts`, `index.wxml`, `index.wxss`, `index.json`. Home edits preserve profile/member/invite/logout behavior and existing tests.

### Task 7: Category and fund-account vertical slice

**Files:**
- Create `server/common/validation/{BookkeepingValidation,StrictRequest}.java`, `server/ledger/LedgerReadGuard.java`.
- Create `server/category/{CategoryController,CategoryService,CategoryModels}.java`, `server/category/store/{CategoryStore,JdbcCategoryStore}.java`.
- Create `server/account/{AccountController,AccountService,AccountModels}.java`, `server/account/store/{AccountStore,JdbcAccountStore}.java`.
- Modify `server/common/error/ErrorCode.java` only for specific new errors if needed.
- Create `test/common/validation/BookkeepingValidationTests.java`, `test/category/{CategoryServiceTests,CategoryHttpTests,JdbcCategoryStoreTests}.java`, `test/account/{AccountServiceTests,AccountHttpTests,JdbcAccountStoreTests}.java`, `test/ledger/LedgerReadGuardTests.java`. A dedicated `test/database/CatalogMysqlIntegrationTests.java` may contain guarded real-SQL/transaction proofs; do not execute its reset gate.
- Create `mp/types/catalog.ts`, `mp/services/catalog.ts`, `mp/flows/catalog-flow.ts`, `mp/utils/page-guard.ts`, `mp/pages/{category-list,category-edit,account-list,account-edit}/index.{ts,wxml,wxss,json}`.
- Modify `mp/runtime.ts`, `mp/app.json`, `mp/pages/home/index.{ts,wxml,wxss}`; update existing tests' runtime fixtures compatibly.
- Create `account-book-miniapp/tests/{catalog-api,catalog-pages}.spec.ts` (use existing test naming conventions when discovering them).

**Interfaces:**
- Consume `LedgerWriteGuard.lock().requireActor(CurrentUser)`, `AuditLogService.append(AuditEvent)`, AuthStore/MemberStore consistent reads, existing HttpClient, SessionStore, SessionFlow.
- Produce `LedgerReadGuard.requireActor(CurrentUser): MemberStore.MemberState`, invoked inside service `@Transactional(readOnly=true,isolation=REPEATABLE_READ)`.
- Produce `BookkeepingValidation.safeId(long):long`, `money(String,boolean allowZeroOrNegative):BigDecimal`, `moneyText(BigDecimal):String`, `date(String):LocalDate`, `version(Long):long`. Additional cohesive validation helpers allowed in the same class.
- Category JSON `{id,entryType,name,icon,color,sortNo,systemDefault,status}`; account JSON `{id,name,accountType,initialBalance,currentBalance,sortNo,status,version}`. Collection `{items:[...]}`.
- Create category `{entryType,name,icon,color,sortNo,status}`; update `{name,icon,color,sortNo,status}`. Create account `{name,accountType,initialBalance,sortNo,status}`; update `{name,sortNo,status,version}`; account DELETE `?version=N`. Optional create sortNo/status defaults to 0/ACTIVE, icon/color default null. Name/type/money required.
- Category API GET/POST `/api/v1/categories`, GET/PUT/PATCH/DELETE `/{id}`. Account same `/api/v1/accounts`. Filter `entryType` (category), `status`; omit means all. Types immutable; unknown or immutable body fields reject 400.
- Runtime `catalog: CatalogFlow`; flow exposes `categories(entryType?,status?)`, `category(id)`, `createCategory(body)`, `updateCategory(id,body)`, `deleteCategory(id)`, `accounts(status?)`, `account(id)`, `createAccount(body)`, `updateAccount(id,body)`, `deleteAccount(id,version)`.

- [ ] Write failing validation/service tests: exact decimals (0.10+0.20=0.30), single money max 9999999999999.99, initial negative allowed, non-string/exponent/3 decimals rejected; real dates/leap years; trimmed name, sort 0/32767; immutable fields rejected. Example expected assertions:
```java
assertEquals("0.30", BookkeepingValidation.moneyText(new BigDecimal("0.10").add(new BigDecimal("0.20"))));
assertThrows(BusinessException.class, () -> BookkeepingValidation.money("1e2", false));
assertThrows(BusinessException.class, () -> BookkeepingValidation.date("2025-02-29"));
```
- [ ] Run focused tests before production implementation using `./mvnw.cmd -o -Dtest=BookkeepingValidationTests,CategoryServiceTests,AccountServiceTests test`; record the expected missing implementation/assertion RED, not a broken dependency as RED.
- [ ] Implement shared validation and snapshot read guard with the existing invariant checks, then category and account stores and services. JDBC lookups always restrict ledger_id=1; duplicate name exceptions map to explicit 409. Writes use the same ledger lock order and latest actor role, audit MANDATORY same transaction, generated IDs checked for JS safety.
```sql
SELECT fa.id, fa.initial_balance + COALESCE(SUM(CASE WHEN e.entry_type='INCOME' THEN e.amount ELSE -e.amount END),0) AS current_balance
FROM fund_account fa LEFT JOIN book_entry e ON e.account_id=fa.id AND e.ledger_id=fa.ledger_id AND e.deleted_at IS NULL
WHERE fa.ledger_id=1 GROUP BY fa.id;
SELECT COUNT(*) FROM book_entry WHERE ledger_id=1 AND category_id=?;
UPDATE fund_account SET name=?,sort_no=?,status=?,version=version+1,updated_at=? WHERE ledger_id=1 AND id=? AND version=?;
```
- [ ] Add HTTP tests for unauthenticated/member/admin, GET/PUT/PATCH binding, required account version, forged immutable fields, numeric JSON money rejection, invalid filters and exact string serialization. Avoid globally changing old Jackson coercion behavior: new DTOs must be strict locally.
- [ ] Add store-query tests for name scope, sortNo/id ordering, balance formula and soft-delete filter, reference checks that INCLUDE deleted rows. Service tests must assert no store mutation/audit on denial and audit failure propagates; a transaction-aware fake or explicitly gated MySQL test proves rollback rather than Mockito state illusions.
- [ ] Add miniapp failing tests for API paths/encoding, list filters, member read-only view, delete confirmation, form retention on failure, stale account version, old response after hide/session switch, and editing immutable fields absent. Page tests exercise registered Page handlers with the existing test harness.
```ts
expect(request.method).toBe('PUT')
expect(request.url).toContain('/api/v1/accounts/7')
expect(request.data).toEqual({ name: '现金', sortNo: 0, status: 'ACTIVE', version: 2 })
```
- [ ] Implement four pages and catalog client/flow/runtime bindings; home provides category/account entry points for all readers and management controls only OWNER/ADMIN. Type/initial balance editable on create only; show disabled resources and initial/current balances, do not calculate currency using Number. Creation/edit/network/conflict/empty views are distinct. Native modal callbacks check current session and page before writes.
- [ ] Run focused GREEN then full server offline regression, miniapp `npm.cmd test` and `npm.cmd run typecheck` (already checks both TS configs). No `typecheck:test` script exists. Self-review scoped diff and write Task 7 report with RED/GREEN commands, counts, changed files, unexecuted real-DB proof and known inherited warnings. Parent independent review gate required before Task 8. No commit.

### Task 8: Income/expense entry vertical slice

**Files:**
- Create `server/entry/{EntryController,EntryService,EntryModels,EntryFilters}.java`, `server/entry/store/{EntryStore,JdbcEntryStore}.java`.
- Modify shared bookkeeping validation only for entry/date/UUID helpers, ErrorCode for entry-specific conflict codes.
- Create `test/entry/{EntryServiceTests,EntryHttpTests,JdbcEntryStoreTests,EntryFiltersTests}.java`, optionally guarded `test/database/EntryMysqlIntegrationTests.java`.
- Create `mp/types/entry.ts`, `mp/services/entry.ts`, `mp/flows/entry-flow.ts`, `mp/utils/bookkeeping.ts`, `mp/pages/{entry-create,entry-list,entry-detail,entry-edit}/index.{ts,wxml,wxss,json}`. Share entry create/edit form logic in `mp/flows/entry-form.ts` when needed; no duplicated large blocks.
- Modify `mp/runtime.ts`, `mp/app.json`, home entry links and corresponding existing fixtures; add `account-book-miniapp/tests/{entry-api,entry-flow,entry-pages}.spec.ts`.

**Interfaces:**
- Consume Task 7 LedgerReadGuard and BookkeepingValidation exact methods; schema dictionary lookups from own store or category/account store interfaces. Runtime.catalog unchanged.
- Produce `GET/POST /api/v1/entries`, `GET/PUT/PATCH/DELETE /api/v1/entries/{id}`, `GET /api/v1/entries/creators`; DELETE `?version=N`.
- Entry view `{id,entryType,amount,categoryId,categoryName,categoryStatus,accountId,accountName,accountStatus,entryDate,note,createdBy,creatorName,createdAt,updatedAt,version,canEdit,canDelete}`. Include normalized `clientRequestId` when useful for replay; never expose auth secrets. Creator option `{userId,displayName}`. Collections `{items,page,pageSize,total}`, creators `{items}`.
- Create `{entryType,amount,categoryId,accountId,entryDate,note,clientRequestId}`, edit same business fields plus version (no clientRequestId). In all money is string, IDs safe integers, version nonnegative integer.
- Filters `{dateFrom?,dateTo?,entryType?,categoryId?,accountId?,createdBy?,keyword?,page?,pageSize?}`. Default all dates, page 1,size20,max50, literal note keyword escaped, inclusive UI dates, SQL safe max date.
- Runtime `entries: EntryFlow`; `list(filters)`, `detail(id)`, `creators()`, `create(body)`, `update(id,body)`, `remove(id,version)` return typed API values. EntryFlow may own intent tracking in a separate EntryCreateIntent class: snapshot payload + UUID per intent, no storage.

- [ ] Write failing service/filter tests for first-write-wins UUID, other-actor key conflict, deleted-key conflict, stale update/delete version, member own-only mutation, removed member denial, disabled unchanged refs allowed but new/changed refs denied, direction mismatch, exact precision/date/note bounds. Include denial/audit assertions.
```java
var first = service.create(owner, request("3.40", UUID_A), "req-a");
var replay = service.create(owner, request("9.99", UUID_A), "req-b");
assertEquals(first.id(), replay.id());
assertEquals("3.40", replay.amount());
```
- [ ] Run focused RED, then implement stores and transactional service. Lock ledger before UUID or resources; on replay authenticate current actor first and return existing CURRENT state without mutation/audit duplicate. Existing soft-deleted UUID never resurrects. Strict DTOs reject creator/ledger/idempotency-field forgery. UUID normalized lowercase canonical; payload validation does not produce rounded money.
```sql
UPDATE book_entry SET entry_type=?,amount=?,category_id=?,account_id=?,entry_date=?,note=?,updated_by=?,updated_at=?,version=version+1
WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?;
UPDATE book_entry SET deleted_at=?,updated_at=?,updated_by=?,version=version+1
WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?;
```
- [ ] Add read snapshot identity checks and parameterized list/count/detail queries. Historical names join by ledger/resource IDs without ACTIVE restriction, creator display_name fallback nickname. Sort `entry_date DESC,id DESC`. `creators` union active ledger members with historical entry creators (including soft-deleted history acceptable for stable filter options), returning only IDs/display names. No financial data from another ledger through forged IDs.
- [ ] Add HTTP/store tests for all paths and filters, ascending invalid ranges, overflow pagination, max-date handling, unknown body fields, `keyword='%_\\'` literal escaping. Add guarded MySQL proofs for idempotency uniqueness, balances after edits/deletes and audit rollback if not covered by transaction-aware offline integration; keep its reset disabled.
- [ ] Add failing miniapp tests before form code: UUID retained and exact payload locked after timeout/network/unknown server response, manual retry only, second tap deduped, abandon prompts, conflict retains edits, delete confirm cancellation, stale response after hide/logout, role controls, original disabled options. Read and edit IDs are validated; HTTP methods stay existing GET/POST/PUT/DELETE.
```ts
await intent.submit(form).catch(() => undefined)
await intent.retry()
expect(create.mock.calls[1][0]).toEqual(create.mock.calls[0][0])
expect(create.mock.calls[1][0].clientRequestId).toBe(create.mock.calls[0][0].clientRequestId)
```
- [ ] Implement four pages and shared form/intent logic, date default Asia/Shanghai, no persistent financial drafts. Entry list includes all approved filters, creators picker (not current-members-only), clear/apply pagination reset. Create/edit fetch active dicts; edit also displays the original disabled association. Success navigation to detail and list refresh. Deleted rows return clear not-found. Conflict reload is explicit and confirmed when discarding edits.
- [ ] Run focused GREEN, full offline backend/miniapp tests and both TS checks, self-review, report exact evidence. Parent independent review must verify first-write-wins, transaction safety and stale UI handling before Task 9. No package/restart/commit or real DB reset.

### Task 9: Home and monthly statistics vertical slice

**Files:**
- Create `server/statistics/{StatisticsController,StatisticsService,StatisticsModels,StatisticsPeriod}.java`, `server/statistics/store/{StatisticsStore,JdbcStatisticsStore}.java`.
- Create `test/statistics/{StatisticsServiceTests,StatisticsHttpTests,JdbcStatisticsStoreTests,StatisticsPeriodTests}.java`, optionally guarded `test/database/StatisticsMysqlIntegrationTests.java`.
- Create `mp/types/statistics.ts`, `mp/services/statistics.ts`, `mp/flows/statistics-flow.ts`, `mp/pages/statistics/index.{ts,wxml,wxss,json}`.
- Modify `mp/runtime.ts`, `mp/app.json`, `mp/pages/home/index.{ts,wxml,wxss}`, home tests; add `account-book-miniapp/tests/{statistics-api,statistics-pages,home-bookkeeping}.spec.ts`.
- Update `docs/07-微信共享记账小程序最终开发执行计划.md`; create `docs/17-分类账户记账与统计实施记录.md` with the four separate evidence levels and manual acceptance steps. Do not mark runtime or real DB checks passed without evidence.

**Interfaces:**
- Consume LedgerReadGuard snapshot membership, moneyText, entry schema and Runtime.entries.list({page:1,pageSize:5}) for home recent ALL-date entries.
- Five GET endpoints under `/api/v1/statistics`: `monthly-summary`, `daily-trend`, `categories`, `accounts`, `members`. `month=YYYY-MM` optional, ranking `entryType=INCOME|EXPENSE` default EXPENSE. All receive selected month, only ranking endpoints receive direction.
- Summary `{month,income,expense,net,entryCount}`; daily `{month,items:[{date,income,expense,net,entryCount}]}`; category/member `{month,entryType,total,items:[{id,name,amount,percentage,entryCount}]}`; accounts `{month,items:[{id,name,income,expense,net,entryCount}]}`. Money and percentage fixed-two decimal strings; entryCount/counts numeric. Percent range 0–100; denominator selected-direction total; ROUND_HALF_UP display only (not monetary inputs).
- Runtime `statistics: StatisticsFlow`; methods `summary(month?)`, `daily(month?)`, `categories(month?,entryType?)`, `accounts(month?)`, `members(month?,entryType?)`. Independent read endpoints, no hidden new writes/cache.

- [ ] Write failing StatisticsPeriod and service tests: strict month, default Clock in Asia/Shanghai, leap Feb, crossyear, 1000-01/9999-12, zero-filled daily rows, decimal exactness, sort amount DESC/id ASC, zero-denominator no NaN, removed creator present, disabled dictionary retained, soft-delete excluded, account initial balance excluded. Concrete fixture: September income 100.10+0.20, expense30.05; summary income100.30,expense30.05,net70.25, count3; deleted expense9.99 and Aug expense8.00 excluded.
```java
assertEquals("100.30", summary.income());
assertEquals("30.05", summary.expense());
assertEquals("70.25", summary.net());
assertEquals(29, service.daily(actor, "2024-02").items().size());
```
- [ ] Run focused RED then implement period parser and aggregation stores. Month SQL binds `entry_date>=? AND entry_date<?` and `deleted_at IS NULL` with ledger1; 9999-12 omit impossible upper bound. No `DATE_FORMAT` on filter column needed. Each endpoint verifies latest actor in a REPEATABLE_READ snapshot. Category/account join keeps old disabled refs; member grouping is created_by→app_user + left ledger_member on ledger/user without ACTIVE filter.
```sql
SELECT e.created_by, COALESCE(NULLIF(TRIM(lm.display_name),''),u.nickname) AS name, SUM(e.amount) AS amount, COUNT(*) AS entry_count
FROM book_entry e JOIN app_user u ON u.id=e.created_by LEFT JOIN ledger_member lm ON lm.ledger_id=e.ledger_id AND lm.user_id=e.created_by
WHERE e.ledger_id=1 AND e.deleted_at IS NULL AND e.entry_date>=? AND e.entry_date<? AND e.entry_type=?
GROUP BY e.created_by, name ORDER BY amount DESC,e.created_by ASC;
```
- [ ] Add HTTP and query tests for five exact paths, safe invalid inputs, all monetary JSON strings; prove selected-month account net excludes initialBalance and recent5 is fetched without current-month restriction. Gated real MySQL aggregation fixture tests remain unexecuted without exclusive cleanup approval.
- [ ] Add miniapp failing tests for month/direction switching and stale out-of-order response, session/hide guards, home summary failure not zero, recent request independent of month, loading/empty/readerror distinction, preserved profile/member/invite/logout navigation.
```ts
expect(entries.list).toHaveBeenCalledWith({ page: 1, pageSize: 5 })
expect(page.data.summaryError).not.toBe('')
expect(page.data.summary).toBeNull()
```
- [ ] Implement statistics client/flow/page and home cards. Native cards/daily rows/ranking percentage bars suffice; no chart library. Clear old numeric data on failed current snapshot, show retry. Statistics month picker in range, direction picker affects category/member totals only. Home shows monthly income/expense/net, recent5, create/list/stats/catalog and all existing security navigation. Money text comes from API; percentages can be validated numeric for width only, never used to sum money.
- [ ] Run focused GREEN and full offline backend + miniapp regression/typecheck. Write user-facing implementation record and acceptance paths. Parent task review then broad all-three-feature review checks shared contracts, resource security, concurrency, idempotency and aggregate consistency. Resolve findings, rerun covering tests, retain all evidence; no commit, merge, push, reset or secret changes.
- [ ] Record code/offline/real-MySQL/runtime/manual status separately. A runtime update and actual DB test gate must be explicitly reported, never implied by green unit tests. Parent may offer the existing one-key update command after final verification; any real DB reset requires fresh authorization.
