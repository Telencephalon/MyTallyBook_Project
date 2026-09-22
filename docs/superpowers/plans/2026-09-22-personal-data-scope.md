# Personal data scope and unlimited invited membership

> Execution: use superpowers:subagent-driven-development for independent work and review. Main checkout only; no commits, worktrees, deployment, database connections or running-service changes.

## Approved requirements / design

User requests no 10-person cap, invitation-only membership, each account seeing only its own created records, and OWNER seeing everyone or one selected creator. User explicitly selected shared category/account dictionaries maintained by OWNER. Existing `book_entry.created_by` is the account partition key; no historical reassignment or per-user duplicate dictionaries are needed.

- Server verifies current live membership/role before applying scope. MEMBER and ADMIN are personal-data-only; OWNER can query all or a positive `createdBy`. A non-owner asking for a different creator receives ACCESS_DENIED. Details outside personal scope return RESOURCE_NOT_FOUND; writes are denied. Client filtering is never the security boundary.
- Scope covers list/count/detail, recent entries, every statistics aggregate and ALL-range date extent, and account balances. Owner-only category/account writes. Existing invite/member administration remains unchanged; these are membership administration records rather than bookkeeping data.
- OWNER selects creator on home, entry-list and statistics. Filter is visible and local to each page; changing it refreshes data and resets paging. Former creators remain selectable through existing creators endpoint. Non-owners have no creator selector.
- Invitation acceptance keeps expiration/single-use/user-status/unique-owner checks and transaction locks; remove capacity enforcement. API maxMembers becomes nullable (`null` means no cap). Existing max_users/max_members DB columns are legacy metadata, no longer enforced or presented as a cap. Preserve historical migration checksums; no data migration is required for this code rollout.
- Shared account initial balance belongs to the shared dictionary; non-owner displayed balances use only personal entry net, with initialBalance reported as 0. OWNER sees existing initial balance plus all entries. Statistics already represent only entry flows.
- No backend startup or production access. Offline Maven tests, TS/Vitest, native WXML/WXSS checks, followed by independent security review. Real MySQL and deployment explicitly remain pending.

## Tasks / ownership

- [x] Task 1: Unlimited invited membership. Backend auth/bootstrap/invite/member/ledger guards and response contracts, focused tests. Do not edit entry/statistics/account/category or miniapp. Keep legacy DB metadata but remove cap checks. Report changed test expectations and no-production verification.
- [x] Task 2: Backend entry + statistics scope. EntryService/filters/store creators, statistics service/controller/period/store and tests. Shared static `ledger.DataScope.creator(MemberState, Long)` provided by coordinator returns nullable OWNER filter or non-owner id; `canAccess(MemberState,long)` for detail/write. Statistics GET endpoints accept optional createdBy. All period queries must carry that resolved scope, including extent. Preserve legacy overloads for old internal callers/tests but ensure they also resolve role.
- [x] Task 3: Miniapp. maxMembers nullable, unlimited text, remove 10-person onboarding text; OWNER-only shared dictionary management. Owner creator selectors on home/list/statistics; statistics query accepts createdBy. Preserve session/loading/race guards. Update frontend tests.
- [x] Task 4: Coordinator implements shared DataScope and scopes account balances; restrict dictionary mutations to OWNER. Test role matrix, balances, permissions. Integrate backend/frontend contracts and execute tests.
- [x] Task 5: Independent full diff review (security/IDOR, request tampering, stale roles, scoped totals/pagination, shared dictionaries, invite-only and 11th member), fix findings, record final verification and rollout constraints.

## Review focus

1. ADMIN must not inherit former cross-user data access; stale token role must not override live membership.
2. Unauthorized direct IDs, forged createdBy, counts, ALL-range dates and account net must not leak others' data.
3. Current creator vs free-text personName are distinct; filter uses immutable user id.
4. Changing owner selection during a request or being demoted must not render stale cross-user data.
5. Invitation validity and single-use atomicity remain intact beyond 10 members.

## Progress / rulings

- Scope clarified by user: shared OWNER-managed dictionaries, per-account bookkeeping data.
- Preserve global membership/invite admin roles; ADMIN bookkeeping and dictionary-write privileges reduced as required.
- Keep historical schema cap columns unused to avoid changing existing migration checksums or touching production data.
- Prior UI edits remain in working tree and are unrelated; do not reset them.
- Verification: full offline backend 455 total / 429 passed / 26 gated database skips; after idempotency review fix, 35 entry service/HTTP tests passed. Final frontend 398 passed, both TS builds passed, native 22 WXML/25 WXSS passed.
- Independent backend and frontend reviews closed all findings: foreign-idempotency deletion status, catalogue first-load retry disabled, and pending-save role-change continuation. Six frontend recovery/demotion scenarios independently verified.
- No real DB, runtime service, JAR replacement, or deployment performed. Final Chinese implementation/API/rollout record: `docs/27-个人数据隔离及不限人数实施记录.md`.
