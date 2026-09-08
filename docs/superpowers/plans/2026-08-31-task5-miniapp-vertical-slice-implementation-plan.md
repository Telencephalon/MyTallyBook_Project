# Task 5 Miniapp Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first production-shaped WeChat Mini Program vertical slice for session restoration, WeChat login, one-time owner bootstrap, fixed-ledger home, nickname editing, and secure logout.

**Architecture:** Native WeChat TypeScript pages call a typed session-flow coordinator. The coordinator composes focused auth/user/ledger services over one HTTP client and a versioned local session store; no runtime UI or state-management framework is added. Pure TypeScript boundaries and injected WeChat adapters make the flow testable under Node.js without calling real WeChat or production services.

**Tech Stack:** WeChat native Mini Program, TypeScript 5.9.3, Node.js 24.20.0 LTS, npm 11.19.0, miniprogram-api-typings 5.2.3, Vitest 4.1.11, @types/node 24.3.0.

**Spec:** [Task 5 小程序首个纵向切片设计规格](../specs/2026-08-31-task5-miniapp-vertical-slice-design.md)

**Execution status (2026-08-31):** Tasks 1～7 and the automated/documentation portions of Task 8 are complete. The WeChat Developer Tools verification in Task 8 Step 5 remains pending and is recorded in `docs/10-微信小程序首个纵向切片实施记录.md`; unchecked boxes below preserve the original execution checklist rather than claiming unperformed manual evidence.

## Global Constraints

- Use only native WeChat components at runtime; add no UI framework or runtime state-management package.
- The profile page edits nickname only; do not add avatar upload, object storage, `chooseAvatar`, or an avatar URL input.
- Persist only `{ version: 1, token, expiresAt }` under `mytallybook.session.v1`; never persist user, ledger, WeChat code, bootstrap key, AppSecret, openid, `session_key`, or request bodies.
- Development API base URL is exactly `http://127.0.0.1:8080`.
- Trial and release builds must reject requests until an explicit备案 HTTPS domain is configured; never fall back to a public IP, HTTP, or port `7631`.
- Do not modify MySQL, Nginx, systemd, production networking, or 京东云 services. The only confirmed backend compatibility change is adding `PUT` to the existing nickname-update controller method while retaining `PATCH` and the same validation/service/audit contract.
- Do not connect to the production database or real production API during automated tests.
- Do not commit or push. The user explicitly requested preservation as uncommitted work; every task ends with tests plus `git diff --check` instead of a commit.
- Do not overwrite unrelated Task 1～4 changes already present in the isolated worktree.
- All new development documentation must remain Markdown.

---

## File Responsibility Map

| File | Responsibility |
|---|---|
| `miniprogram/types/api.ts` | Backend envelopes, auth state, user, ledger, profile input types |
| `miniprogram/types/error.ts` | Normalized `AppError` used by every service and page |
| `miniprogram/config/env.ts` | Resolve `develop`/`trial`/`release` API configuration with deployment guard |
| `miniprogram/store/session.ts` | Versioned Token persistence and in-memory user/ledger context |
| `miniprogram/services/http.ts` | Request ID, timeout, auth header, response validation, error normalization, 401 gate |
| `miniprogram/services/wechat.ts` | Promise adapter for `wx.login` |
| `miniprogram/services/auth.ts` | Login, bootstrap, logout endpoint calls |
| `miniprogram/services/user.ts` | Current-user read and nickname update endpoint calls |
| `miniprogram/services/ledger.ts` | Fixed-ledger read endpoint call |
| `miniprogram/flows/session-flow.ts` | Restore/login/bootstrap/context/logout orchestration |
| `miniprogram/runtime.ts` | Compose production adapters and singleton services without exposing secrets |
| `miniprogram/utils/validation.ts` | Shared bootstrap-key and nickname normalization rules |
| `miniprogram/utils/presentation.ts` | Safe page error view and role label |
| `miniprogram/pages/*` | Page state, user events, presentation, and route transitions only |
| `tests/*` | Node-side tests with injected fake Storage, request, login, and navigation adapters |

---

### Task 1: Establish the Test Harness, API Types, and Environment Gate

**Files:**

- Modify: `account-book-miniapp/package.json`
- Modify: `account-book-miniapp/package-lock.json`
- Modify: `account-book-miniapp/tsconfig.json`
- Create: `account-book-miniapp/tsconfig.test.json`
- Create: `account-book-miniapp/vitest.config.mts`
- Create: `account-book-miniapp/miniprogram/types/api.ts`
- Create: `account-book-miniapp/miniprogram/types/error.ts`
- Create: `account-book-miniapp/miniprogram/config/env.ts`
- Test: `account-book-miniapp/tests/config/env.test.ts`

**Interfaces:**

- Produces: `ApiResponse<T>`, `ApiErrorResponse`, `AuthStateData`, `UserProfile`, `Ledger`, `UpdateProfileInput`.
- Produces: `AppError`, `AppErrorKind`.
- Produces: `resolveApiEnvironment(envVersion, deployedBaseUrl?)` and `getApiEnvironment()`.

- [ ] **Step 1: Install and lock the test-only dependencies**

Run from `account-book-miniapp`:

```powershell
npm install --save-dev --save-exact vitest@4.1.11 @types/node@24.3.0
```

Set the scripts exactly to:

```json
{
  "scripts": {
    "test": "vitest run",
    "test:watch": "vitest",
    "typecheck": "tsc --noEmit -p tsconfig.json && tsc --noEmit -p tsconfig.test.json"
  }
}
```

Keep all existing exact dependency versions and the existing Node engine range.

- [ ] **Step 2: Configure Vitest and TypeScript coverage**

Create `vitest.config.mts`:

```ts
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    clearMocks: true,
    restoreMocks: true,
  },
})
```

Keep `tsconfig.json` dedicated to the Mini Program: `types` contains only `miniprogram-api-typings`, and `include` contains `miniprogram/**/*.ts` plus `typings/index.d.ts`. Create `tsconfig.test.json` extending it with `module: ESNext`, `moduleResolution: Bundler`, `types: [miniprogram-api-typings, node]`, and `skipLibCheck: true`; include source, tests, `typings/index.d.ts`, and `vitest.config.mts`. This separation preserves strict checking of project code while avoiding conflicts between Node and WeChat third-party global declaration files.

- [ ] **Step 3: Write failing environment tests**

```ts
import { describe, expect, it } from 'vitest'
import { resolveApiEnvironment } from '../../miniprogram/config/env'

describe('resolveApiEnvironment', () => {
  it('uses the loopback backend only for develop builds', () => {
    expect(resolveApiEnvironment('develop')).toEqual({
      baseUrl: 'http://127.0.0.1:8080',
      timeoutMs: 10_000,
    })
  })

  it.each(['trial', 'release'])('blocks %s when HTTPS domain is absent', envVersion => {
    expect(captureError(() => resolveApiEnvironment(envVersion)))
      .toMatchObject({ code: 'API_DOMAIN_NOT_CONFIGURED' })
  })

  it('accepts an HTTPS domain for trial and release', () => {
    expect(resolveApiEnvironment('release', 'https://api.mytallybook.example')).toEqual({
      baseUrl: 'https://api.mytallybook.example',
      timeoutMs: 10_000,
    })
  })

  it.each(['http://api.example.com', 'https://117.72.101.42', 'https://localhost'])
    ('rejects unsafe deployed address %s', deployedBaseUrl => {
      expect(captureError(() => resolveApiEnvironment('release', deployedBaseUrl)))
        .toMatchObject({ code: 'API_DOMAIN_INVALID' })
    })
})
```

Define `captureError` in the test so a missing throw still fails:

```ts
function captureError(action: () => unknown): unknown {
  try {
    action()
  } catch (error) {
    return error
  }
  throw new Error('Expected action to throw')
}
```

- [ ] **Step 4: Run the focused test and verify the red state**

Run:

```powershell
npm test -- tests/config/env.test.ts
```

Expected: FAIL because `miniprogram/config/env.ts` does not exist.

- [ ] **Step 5: Add exact API and error types**

`types/api.ts` must export:

```ts
export type AuthState = 'AUTHENTICATED' | 'NEED_BOOTSTRAP' | 'INVITE_REQUIRED'
export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface ApiResponse<T> {
  code: 'OK'
  message: string
  data: T
  requestId: string
  timestamp: string
}

export interface ApiErrorResponse {
  code: string
  message: string
  details?: unknown
  requestId: string
  timestamp: string
}

export interface AuthStateData {
  state: AuthState
  token: string | null
  expiresAt: string | null
}

export interface UserProfile {
  userId: number
  nickname: string
  avatarUrl: string | null
  ledgerId: number
  memberId: number
  role: MemberRole
  displayName: string
}

export interface Ledger {
  id: number
  name: string
  currency: string
  timezone: string
  maxMembers: number
}

export interface UpdateProfileInput {
  nickname: string
}
```

`types/error.ts` must define the normalized error without retaining a request or response body:

```ts
export type AppErrorKind = 'HTTP' | 'NETWORK' | 'TIMEOUT' | 'CONFIG' | 'INVALID_RESPONSE'

export class AppError extends Error {
  constructor(
    public readonly kind: AppErrorKind,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
    public readonly statusCode?: number,
  ) {
    super(message)
    this.name = 'AppError'
  }
}
```

- [ ] **Step 6: Implement the environment guard**

`env.ts` must keep the deployed value empty until the real备案 domain exists:

```ts
import { AppError } from '../types/error'

const DEPLOYED_API_BASE_URL = ''
const TIMEOUT_MS = 10_000

export interface ApiEnvironment {
  baseUrl: string
  timeoutMs: number
}

export function resolveApiEnvironment(
  envVersion: string,
  deployedBaseUrl = DEPLOYED_API_BASE_URL,
): ApiEnvironment {
  if (envVersion === 'develop') {
    return { baseUrl: 'http://127.0.0.1:8080', timeoutMs: TIMEOUT_MS }
  }
  if (!deployedBaseUrl) {
    throw new AppError('CONFIG', 'API_DOMAIN_NOT_CONFIGURED', '体验版或正式版 API 域名尚未配置')
  }
  const hostMatch = /^https:\/\/([^/:]+)(?::443)?\/?$/.exec(deployedBaseUrl)
  const host = hostMatch?.[1] ?? ''
  if (!host || host === 'localhost' || /^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
    throw new AppError('CONFIG', 'API_DOMAIN_INVALID', 'API 地址必须是备案后的 HTTPS 域名')
  }
  return { baseUrl: deployedBaseUrl.replace(/\/$/, ''), timeoutMs: TIMEOUT_MS }
}

export function getApiEnvironment(): ApiEnvironment {
  const envVersion = wx.getAccountInfoSync().miniProgram.envVersion
  return resolveApiEnvironment(envVersion)
}
```

- [ ] **Step 7: Run focused and type checks**

Run:

```powershell
npm test -- tests/config/env.test.ts
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: environment tests PASS, TypeScript exits 0, diff check produces no output.

---

### Task 2: Build the Versioned Session Store

**Files:**

- Create: `account-book-miniapp/miniprogram/store/session.ts`
- Test: `account-book-miniapp/tests/store/session.test.ts`

**Interfaces:**

- Consumes: `AuthStateData`, `UserProfile`, `Ledger`, `AppError`.
- Produces: `SESSION_STORAGE_KEY`, `StorageAdapter`, `SessionStore`, singleton `sessionStore`.
- Produces methods: `hydrate()`, `saveAuthenticated(auth)`, `setContext(user, ledger)`, `getToken()`, `getUser()`, `getLedger()`, `clear()`.

- [ ] **Step 1: Write failing session tests with an in-memory adapter**

The test must cover valid hydration, expiry, malformed data, unsupported version, incomplete authentication, in-memory context, and clearing:

```ts
const storage = new Map<string, unknown>()
const adapter = {
  get: (key: string) => storage.get(key),
  set: (key: string, value: unknown) => storage.set(key, value),
  remove: (key: string) => storage.delete(key),
}
const now = Date.parse('2026-08-31T00:00:00Z')
const store = new SessionStore(adapter, () => now)

expect(store.hydrate()).toBe(false)

store.saveAuthenticated({
  state: 'AUTHENTICATED',
  token: 'raw-session-token',
  expiresAt: '2026-09-01T00:00:00Z',
})
expect(storage.get(SESSION_STORAGE_KEY)).toEqual({
  version: 1,
  token: 'raw-session-token',
  expiresAt: '2026-09-01T00:00:00Z',
})
```

Add separate assertions that expired or malformed values are removed, and that `NEED_BOOTSTRAP` or null Token throws `INVALID_AUTH_RESPONSE` without writing Storage.

- [ ] **Step 2: Run the focused test and verify the red state**

```powershell
npm test -- tests/store/session.test.ts
```

Expected: FAIL because `SessionStore` is not implemented.

- [ ] **Step 3: Implement the minimal store**

Use these exact boundaries:

```ts
export const SESSION_STORAGE_KEY = 'mytallybook.session.v1'

export interface StorageAdapter {
  get(key: string): unknown
  set(key: string, value: unknown): void
  remove(key: string): void
}

interface PersistedSession {
  version: 1
  token: string
  expiresAt: string
}

export class SessionStore {
  private token: string | null = null
  private expiresAt: string | null = null
  private user: UserProfile | null = null
  private ledger: Ledger | null = null

  constructor(
    private readonly storage: StorageAdapter,
    private readonly now: () => number = Date.now,
  ) {}

  hydrate(): boolean
  saveAuthenticated(auth: AuthStateData): void
  setContext(user: UserProfile, ledger: Ledger): void
  getToken(): string | null
  getUser(): UserProfile | null
  getLedger(): Ledger | null
  clear(): void
}
```

Validate persisted data with explicit property and type checks. Treat `Date.parse(expiresAt) <= now()` or a non-finite parsed value as expired. `clear()` must always remove Storage even when memory is already empty.

Create the WeChat adapter without accessing `wx` until a method executes:

```ts
const wechatStorage: StorageAdapter = {
  get: key => wx.getStorageSync(key) as unknown,
  set: (key, value) => wx.setStorageSync(key, value),
  remove: key => wx.removeStorageSync(key),
}

export const sessionStore = new SessionStore(wechatStorage)
```

- [ ] **Step 4: Run session and type checks**

```powershell
npm test -- tests/store/session.test.ts
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0.

---

### Task 3: Implement the Safe HTTP Client

**Files:**

- Create: `account-book-miniapp/miniprogram/services/http.ts`
- Test: `account-book-miniapp/tests/services/http.test.ts`

**Interfaces:**

- Consumes: `ApiResponse<T>`, `ApiErrorResponse`, `AppError`, `ApiEnvironment`.
- Produces: `RequestData`, `RequestExecutor`, `HttpRequestOptions<TBody>`, `HttpClient`, `createRequestId()`.
- Produces method: `request<TData, TBody = unknown>(options): Promise<TData>`.

- [ ] **Step 1: Write failing request ID and request-shape tests**

Use an injected executor that captures options and calls `success`:

```ts
const executor: RequestExecutor = options => {
  captured = options
  options.success({
    statusCode: 200,
    data: {
      code: 'OK',
      message: 'success',
      data: { id: 1 },
      requestId: 'server-id',
      timestamp: '2026-08-31T00:00:00Z',
    },
    header: { 'X-Request-Id': 'server-id' },
  })
}
const client = new HttpClient({
  environment: { baseUrl: 'http://127.0.0.1:8080', timeoutMs: 10_000 },
  getToken: () => 'session-token',
  onUnauthorized: () => undefined,
  executor,
})
expect(await client.request<{ id: number }>({ method: 'GET', path: '/api/v1/users/me' }))
  .toEqual({ id: 1 })
expect(captured.header.Authorization).toBe('Bearer session-token')
expect(captured.header['X-Request-Id']).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
```

Add cases for unauthenticated requests omitting Authorization, POST JSON data, and base URL joining.

- [ ] **Step 2: Write failing error normalization tests**

Cover:

- HTTP 403 with JSON error preserves `code`, `message`, `requestId`, and `statusCode`.
- HTTP error with invalid body takes request ID from response header.
- 2xx invalid envelope throws `INVALID_SERVER_RESPONSE`.
- `fail({ errMsg: 'request:fail timeout' })` becomes `TIMEOUT`.
- other `fail` becomes `NETWORK`.
- two concurrent authenticated 401 responses call `onUnauthorized` once.
- the next successful response resets the 401 gate.
- no error object retains request body, Token, or response data.

- [ ] **Step 3: Run the HTTP tests and verify the red state**

```powershell
npm test -- tests/services/http.test.ts
```

Expected: FAIL because `HttpClient` does not exist.

- [ ] **Step 4: Implement the injected request boundary**

Define a minimal WeChat-independent adapter:

```ts
export interface RawResponse {
  statusCode: number
  data: unknown
  header: Record<string, string>
}

export interface RawRequestOptions {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: RequestData
  header: Record<string, string>
  timeout: number
  success(response: RawResponse): void
  fail(error: { errMsg: string }): void
}

export type RequestExecutor = (options: RawRequestOptions) => void

export type RequestData = string | WechatMiniprogram.IAnyObject | ArrayBuffer

export interface HttpRequestOptions<TBody extends RequestData> {
  method: RawRequestOptions['method']
  path: `/api/v1/${string}`
  body?: TBody
  authenticated?: boolean
}
```

`HttpClient` defaults `authenticated` to true, rejects missing Token with `AUTHENTICATION_REQUIRED`, validates the success envelope, and never logs request or response values.

Use a client request ID shaped as:

```ts
export function createRequestId(now = Date.now(), random = Math.random()): string {
  return `mp-${now.toString(36)}-${Math.floor(random * 0x100000000).toString(36)}`
}
```

Implement case-insensitive response-header lookup and map invalid/non-JSON failures to `AppError` without retaining original payloads.

- [ ] **Step 5: Add the `wx.request` adapter**

Export:

```ts
export const wechatRequestExecutor: RequestExecutor = options => {
  wx.request({
    url: options.url,
    method: options.method,
    data: options.data,
    header: options.header,
    timeout: options.timeout,
    success: response => options.success({
      statusCode: response.statusCode,
      data: response.data,
      header: response.header as Record<string, string>,
    }),
    fail: error => options.fail({ errMsg: error.errMsg }),
  })
}
```

- [ ] **Step 6: Run HTTP, full test, and type checks**

```powershell
npm test -- tests/services/http.test.ts
npm test
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0.

---

### Task 4: Add WeChat/API Services and the Session Flow Coordinator

**Files:**

- Create: `account-book-miniapp/miniprogram/services/wechat.ts`
- Create: `account-book-miniapp/miniprogram/services/auth.ts`
- Create: `account-book-miniapp/miniprogram/services/user.ts`
- Create: `account-book-miniapp/miniprogram/services/ledger.ts`
- Create: `account-book-miniapp/miniprogram/flows/session-flow.ts`
- Create: `account-book-miniapp/miniprogram/utils/validation.ts`
- Create: `account-book-miniapp/miniprogram/runtime.ts`
- Test: `account-book-miniapp/tests/services/wechat.test.ts`
- Test: `account-book-miniapp/tests/flows/session-flow.test.ts`
- Test: `account-book-miniapp/tests/utils/validation.test.ts`

**Interfaces:**

- Consumes: `HttpClient.request`, `SessionStore`, API types, `getApiEnvironment`, `wechatRequestExecutor`.
- Produces: `getWechatCode(executor?)`, `AuthApi`, `UserApi`, `LedgerApi`.
- Produces: `validateBootstrapKey(value)` and `validateNickname(value)`.
- Produces: `SessionFlow`, `EntryDestination`, singleton `runtime`.

- [ ] **Step 1: Write failing WeChat login adapter tests**

```ts
it('returns a non-empty code', async () => {
  const code = await getWechatCode(options => options.success({ code: 'wx-code' }))
  expect(code).toBe('wx-code')
})

it('rejects when WeChat returns no code', async () => {
  await expect(getWechatCode(options => options.success({ code: '' })))
    .rejects.toMatchObject({ code: 'WECHAT_CODE_MISSING' })
})

it('does not expose the native error message', async () => {
  await expect(getWechatCode(options => options.fail({ errMsg: 'native-sensitive-detail' })))
    .rejects.toMatchObject({ code: 'WECHAT_LOGIN_FAILED', message: '无法获取微信登录凭证，请重试' })
})
```

- [ ] **Step 2: Run the WeChat tests and verify the red state**

```powershell
npm test -- tests/services/wechat.test.ts
```

Expected: FAIL because `getWechatCode` does not exist.

- [ ] **Step 3: Implement typed endpoint services**

Use exact endpoint methods:

```ts
export class AuthApi {
  constructor(private readonly http: HttpClient) {}
  login(code: string): Promise<AuthStateData> {
    return this.http.request({
      method: 'POST', path: '/api/v1/auth/wechat/login', body: { code }, authenticated: false,
    })
  }
  bootstrap(code: string, bootstrapKey: string): Promise<AuthStateData> {
    return this.http.request({
      method: 'POST', path: '/api/v1/auth/bootstrap', body: { code, bootstrapKey }, authenticated: false,
    })
  }
  logout(): Promise<{ state: string }> {
    return this.http.request({ method: 'POST', path: '/api/v1/auth/logout' })
  }
}
```

`UserApi` exposes `getMe(): Promise<UserProfile>` and `updateNickname(nickname): Promise<UserProfile>`. Nickname updates use `PUT /api/v1/users/me`, which shares the backend controller contract with the retained PATCH endpoint because `wx.request` does not support PATCH. `LedgerApi` exposes `getFixedLedger(): Promise<Ledger>`. Keep endpoint strings exactly `/api/v1/users/me` and `/api/v1/ledger`.

- [ ] **Step 4: Write failing session-flow tests**

Build fakes for all dependencies and cover:

```ts
expect(await flow.start()).toEqual({ destination: 'HOME' })
expect(fakeSession.getUser()).toEqual(user)
expect(fakeSession.getLedger()).toEqual(ledger)
```

Required cases:

1. Valid hydrated Token loads user and ledger without calling `wx.login`.
2. No Token obtains a code and maps `AUTHENTICATED` to `HOME` after saving Token and context.
3. `NEED_BOOTSTRAP` maps to `BOOTSTRAP` without saving Token.
4. `INVITE_REQUIRED` maps to `INVITE_REQUIRED` without saving Token.
5. Bootstrap trims the key, validates 20～256 characters, obtains a fresh code on every submit, and maps success to `HOME`.
6. Bootstrap rejects invalid client length before calling `wx.login`.
7. Logout success clears the session.
8. Logout 401 clears the session and resolves.
9. Logout network/5xx preserves the session and rejects.

Add `tests/utils/validation.test.ts` with exact trimming and length boundaries:

```ts
expect(validateBootstrapKey(' 12345678901234567890 '))
  .toEqual({ valid: true, value: '12345678901234567890' })
expect(validateBootstrapKey('short'))
  .toEqual({ valid: false, message: '初始化口令长度必须为20至256个字符' })
expect(validateNickname('  新昵称  ')).toEqual({ valid: true, value: '新昵称' })
expect(validateNickname(' '.repeat(3)))
  .toEqual({ valid: false, message: '昵称长度必须为1至64个字符' })
```

- [ ] **Step 5: Run the flow tests and verify the red state**

```powershell
npm test -- tests/flows/session-flow.test.ts
```

Expected: FAIL because `SessionFlow` does not exist.

- [ ] **Step 6: Implement the coordinator**

Use these exact contracts:

```ts
export type EntryDestination = 'HOME' | 'BOOTSTRAP' | 'INVITE_REQUIRED'
export interface EntryResult { destination: EntryDestination }

export class SessionFlow {
  constructor(
    private readonly session: SessionStore,
    private readonly auth: AuthApi,
    private readonly users: UserApi,
    private readonly ledgers: LedgerApi,
    private readonly obtainWechatCode: () => Promise<string>,
  ) {}

  async start(): Promise<EntryResult>
  async bootstrap(bootstrapKey: string): Promise<EntryResult>
  async refreshContext(): Promise<void>
  async logout(): Promise<void>
}
```

`start()` first calls `session.hydrate()`. A valid persisted session calls `refreshContext()` and returns `HOME`; otherwise it obtains one code and calls `auth.login`. Only `AUTHENTICATED` may call `saveAuthenticated`. `refreshContext()` uses `Promise.all([users.getMe(), ledgers.getFixedLedger()])` and writes both results together with `setContext`.

`bootstrap()` must call `validateBootstrapKey()` and then `obtainWechatCode()` within each valid invocation. The shared validation functions return a discriminated union:

```ts
export type ValidationResult =
  | { valid: true; value: string }
  | { valid: false; message: string }
```

An invalid direct `bootstrap()` call throws `AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', validation.message)` without calling WeChat. `logout()` treats only HTTP 401 as an already-invalid session; network, timeout, config, invalid-response, 403, 409, and 5xx errors remain visible and do not clear.

- [ ] **Step 7: Compose the production runtime**

`runtime.ts` must export a lazy `getRuntime()` function that instantiates exactly one session store, HTTP client, service set, and flow after environment validation succeeds. It must not resolve environment configuration during module evaluation. The 401 handler clears once and returns to the login route:

```ts
export interface Runtime {
  session: SessionStore
  flow: SessionFlow
}

let cachedRuntime: Runtime | null = null

export function getRuntime(): Runtime {
  if (cachedRuntime) return cachedRuntime
  const http = new HttpClient({
    environment: getApiEnvironment(),
    getToken: () => sessionStore.getToken(),
    onUnauthorized: () => {
      sessionStore.clear()
      wx.reLaunch({ url: '/pages/login/index' })
    },
    executor: wechatRequestExecutor,
  })
  cachedRuntime = {
    session: sessionStore,
    flow: new SessionFlow(
      sessionStore,
      new AuthApi(http),
      new UserApi(http),
      new LedgerApi(http),
      () => getWechatCode(),
    ),
  }
  return cachedRuntime
}
```

An environment `CONFIG` error therefore reaches the page `try/catch`, is rendered safely, and does not create or cache a partial runtime. Do not silently substitute an address.

- [ ] **Step 8: Run focused, full, and type checks**

```powershell
npm test -- tests/services/wechat.test.ts tests/flows/session-flow.test.ts tests/utils/validation.test.ts
npm test
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0.

---

### Task 5: Build the Login and Bootstrap Pages

**Files:**

- Create: `account-book-miniapp/miniprogram/utils/presentation.ts`
- Create: `account-book-miniapp/miniprogram/pages/login/index.ts`
- Create: `account-book-miniapp/miniprogram/pages/login/index.wxml`
- Create: `account-book-miniapp/miniprogram/pages/login/index.wxss`
- Create: `account-book-miniapp/miniprogram/pages/login/index.json`
- Create: `account-book-miniapp/miniprogram/pages/bootstrap/index.ts`
- Create: `account-book-miniapp/miniprogram/pages/bootstrap/index.wxml`
- Create: `account-book-miniapp/miniprogram/pages/bootstrap/index.wxss`
- Create: `account-book-miniapp/miniprogram/pages/bootstrap/index.json`
- Test: `account-book-miniapp/tests/utils/presentation.test.ts`

**Interfaces:**

- Consumes: `getRuntime().flow.start()`, `flow.bootstrap()`, `AppError`.
- Consumes: shared `validateBootstrapKey(value)` and `validateNickname(value)`.
- Produces: `toErrorView(error)` and `roleLabel(role)` for all pages.

- [ ] **Step 1: Write failing safe-presentation tests**

```ts
expect(toErrorView(new AppError('HTTP', 'BOOTSTRAP_NOT_CONFIGURED', '服务器尚未配置初始化口令', 'req-1', 503)))
  .toEqual({ message: '服务器尚未配置初始化口令', requestId: 'req-1' })
expect(toErrorView(new Error('internal path and stack')))
  .toEqual({ message: '操作失败，请稍后重试', requestId: '' })
```

- [ ] **Step 2: Run presentation tests and verify the red state**

```powershell
npm test -- tests/utils/presentation.test.ts
```

Expected: FAIL because the presentation helpers do not exist.

- [ ] **Step 3: Implement safe presentation helpers**

`toErrorView` may expose only an `AppError.message` and `requestId`; all unknown errors use the fixed fallback. `roleLabel` maps `OWNER` → `所有者`, `ADMIN` → `管理员`, `MEMBER` → `普通成员`. Bootstrap and profile pages import their validation functions from `utils/validation.ts` so UI and flow boundaries enforce identical rules.

- [ ] **Step 4: Implement the login page state machine**

Use page data shaped as:

```ts
type LoginViewState = 'RESTORING' | 'LOGGING_IN' | 'INVITE_REQUIRED' | 'ERROR'

Page({
  data: {
    viewState: 'RESTORING' as LoginViewState,
    errorMessage: '',
    requestId: '',
    busy: false,
  },
  onLoad() { void this.start() },
  async start() {
    if (this.data.busy) return
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    try {
      const result = await getRuntime().flow.start()
      if (result.destination === 'HOME') {
        wx.reLaunch({ url: '/pages/home/index' })
      } else if (result.destination === 'BOOTSTRAP') {
        wx.navigateTo({ url: '/pages/bootstrap/index' })
      } else {
        this.setData({ viewState: 'INVITE_REQUIRED' })
      }
    } catch (error) {
      this.setData({ viewState: 'ERROR', ...toErrorView(error) })
    } finally {
      this.setData({ busy: false })
    }
  },
  retry() { void this.start() },
})
```

The WXML must render one state at a time, include a retry button only for `ERROR`, and show the invite text “你尚未加入共享账本，请向所有者或管理员获取邀请。” without an inactive invite input.

- [ ] **Step 5: Implement the bootstrap page**

Use a password input with `maxlength="256"`, an explicit label, a loading button, and selectable request ID text. The page logic must:

```ts
onBootstrapKeyInput(event: WechatMiniprogram.Input) {
  this.setData({ bootstrapKey: event.detail.value })
},
async submit() {
  if (this.data.busy) return
  const bootstrapKey = this.data.bootstrapKey.trim()
  if (bootstrapKey.length < 20 || bootstrapKey.length > 256) {
    this.setData({ errorMessage: '初始化口令长度必须为20至256个字符', requestId: '' })
    return
  }
  this.setData({ busy: true, errorMessage: '', requestId: '' })
  try {
    const result = await getRuntime().flow.bootstrap(bootstrapKey)
    this.setData({ bootstrapKey: '' })
    if (result.destination === 'HOME') {
      wx.reLaunch({ url: '/pages/home/index' })
    }
  } catch (error) {
    if (error instanceof AppError && error.code === 'ALREADY_INITIALIZED') {
      this.setData({ bootstrapKey: '' })
      wx.reLaunch({ url: '/pages/login/index' })
      return
    }
    this.setData(toErrorView(error))
  } finally {
    this.setData({ busy: false })
  }
}
```

`onUnload` clears `bootstrapKey`. No Console call may log page data or errors.

- [ ] **Step 6: Add page configuration and styles**

Use JSON titles `微信登录` and `初始化账本`. Page WXSS must define actual loading/error/invite/form spacing and disabled-button opacity using shared app classes where possible. Error request IDs use `user-select: text`.

- [ ] **Step 7: Run focused, full, and type checks**

```powershell
npm test -- tests/utils/presentation.test.ts
npm test
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0.

---

### Task 6: Build the Home and Nickname Profile Pages

**Files:**

- Create: `account-book-miniapp/miniprogram/pages/home/index.ts`
- Create: `account-book-miniapp/miniprogram/pages/home/index.wxml`
- Create: `account-book-miniapp/miniprogram/pages/home/index.wxss`
- Create: `account-book-miniapp/miniprogram/pages/home/index.json`
- Create: `account-book-miniapp/miniprogram/pages/profile/index.ts`
- Create: `account-book-miniapp/miniprogram/pages/profile/index.wxml`
- Create: `account-book-miniapp/miniprogram/pages/profile/index.wxss`
- Create: `account-book-miniapp/miniprogram/pages/profile/index.json`
- Test: `account-book-miniapp/tests/flows/profile-flow.test.ts`

**Interfaces:**

- Consumes: `getRuntime().session`, `flow.refreshContext()`, `flow.updateNickname()`, `flow.logout()`, presentation helpers.
- Produces: user-facing home/profile behavior only; no new backend or storage contract.

- [ ] **Step 1: Write failing nickname-flow tests**

Use a fake `UserApi` and real `SessionStore` to verify:

```ts
const updated = await flow.updateNickname('  新昵称  ')
expect(users.updatedNickname).toBe('新昵称')
expect(updated.nickname).toBe('新昵称')
expect(session.getUser()?.nickname).toBe('新昵称')
```

Add cases for empty/65-character values rejected before the API call and unchanged normalized nickname skipping the PATCH while returning the current user.

- [ ] **Step 2: Run the profile-flow test and verify the red state**

```powershell
npm test -- tests/flows/profile-flow.test.ts
```

Expected: FAIL until `updateNickname` satisfies validation and no-op behavior.

- [ ] **Step 3: Implement profile-flow behavior**

`SessionFlow.updateNickname()` must require an authenticated in-memory user, call the shared `validateNickname()` rule, throw `CLIENT_VALIDATION_FAILED` for an invalid direct invocation, skip API when the normalized value is unchanged, call `UserApi.updateNickname()` otherwise, and update the user while retaining the existing ledger:

```ts
this.session.setContext(updatedUser, existingLedger)
return updatedUser
```

Missing context throws `AppError('INVALID_RESPONSE', 'SESSION_CONTEXT_MISSING', '登录状态不完整，请重新登录')`.

- [ ] **Step 4: Implement the home page**

`onShow` reads the latest session context. If absent, call `refreshContext()` once; on success map only real data into page fields:

```ts
this.setData({
  nickname: user.nickname,
  displayName: user.displayName,
  roleLabel: roleLabel(user.role),
  ledgerName: ledger.name,
  currency: ledger.currency,
  timezone: ledger.timezone,
  maxMembers: ledger.maxMembers,
})
```

The WXML must include a ledger card, identity card, “个人资料” button, “退出登录” button, and text explaining that bills/statistics are delivered in subsequent scoped tasks. It must not render a fabricated balance or count.

Logout logic disables the button, calls `flow.logout()`, and only then `reLaunch`es to login. Failure displays `toErrorView(error)` and leaves the session intact.

- [ ] **Step 5: Implement the profile page**

On load, obtain the current user. Render an `<image>` only when `avatarUrl` is non-empty; otherwise render a CSS circular fallback avatar containing `账`. Display role and display name as read-only fields. Bind only nickname input and submit:

```ts
const validation = validateNickname(this.data.nickname)
if (!validation.valid) {
  this.setData({ errorMessage: validation.message, requestId: '' })
  return
}
const user = await getRuntime().flow.updateNickname(validation.value)
this.setData({ nickname: user.nickname, saved: true })
wx.navigateBack()
```

There must be no `open-type="chooseAvatar"`, avatar click handler, file API, or avatar URL input.

- [ ] **Step 6: Add page configuration and styles**

Use JSON titles `共享账本` and `个人资料`. Home and profile style files must use shared card/form/button classes plus page-specific grid and fallback-avatar styles; all async buttons have a visible disabled state.

- [ ] **Step 7: Run focused, full, and type checks**

```powershell
npm test -- tests/flows/profile-flow.test.ts
npm test
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0.

---

### Task 7: Replace the Quickstart App Shell and Remove Dead Sample Code

**Files:**

- Modify: `account-book-miniapp/miniprogram/app.ts`
- Modify: `account-book-miniapp/miniprogram/app.json`
- Modify: `account-book-miniapp/miniprogram/app.wxss`
- Modify: `account-book-miniapp/typings/index.d.ts`
- Delete: `account-book-miniapp/miniprogram/pages/index/index.ts`
- Delete: `account-book-miniapp/miniprogram/pages/index/index.wxml`
- Delete: `account-book-miniapp/miniprogram/pages/index/index.wxss`
- Delete: `account-book-miniapp/miniprogram/pages/index/index.json`
- Delete: `account-book-miniapp/miniprogram/utils/util.ts` only after `rg` confirms it has no reference outside its own file.
- Test: `account-book-miniapp/tests/config/app-config.test.ts`

**Interfaces:**

- Consumes: the four completed page directories.
- Produces: deterministic entry route and shared native visual shell.

- [ ] **Step 1: Write a failing app configuration test**

Read `miniprogram/app.json` as JSON and assert the exact ordered pages:

```ts
expect(config.pages).toEqual([
  'pages/login/index',
  'pages/bootstrap/index',
  'pages/home/index',
  'pages/profile/index',
])
expect(config.window.navigationBarTitleText).toBe('随手账')
```

Also assert each route has `.ts`, `.wxml`, `.wxss`, and `.json` files on disk, and that no route contains `pages/logs/logs` or `pages/index/index`.

- [ ] **Step 2: Run the app configuration test and verify the red state**

```powershell
npm test -- tests/config/app-config.test.ts
```

Expected: FAIL because `app.json` still references the quickstart routes.

- [ ] **Step 3: Replace the app configuration**

Set `app.json` to the exact route list above and retain:

```json
{
  "window": {
    "navigationBarTextStyle": "black",
    "navigationBarTitleText": "随手账",
    "navigationBarBackgroundColor": "#f7f8fa",
    "backgroundColor": "#f7f8fa"
  },
  "style": "v2",
  "componentFramework": "glass-easel",
  "lazyCodeLoading": "requiredComponents"
}
```

Keep `app.ts` side-effect free:

```ts
App<IAppOption>({
  globalData: {},
})
```

Simplify `IAppOption` to contain only `globalData: Record<string, never>` unless page compilation proves the framework requires a wider type.

- [ ] **Step 4: Add the shared visual foundation**

`app.wxss` must define actual values for:

```css
page {
  min-height: 100%;
  background: #f7f8fa;
  color: #1f2329;
  font-size: 28rpx;
}
.page { min-height: 100vh; padding: 32rpx; box-sizing: border-box; }
.card { background: #fff; border-radius: 24rpx; padding: 32rpx; box-shadow: 0 8rpx 30rpx rgba(31, 35, 41, .06); }
.primary-button { background: #1677ff; color: #fff; border-radius: 16rpx; }
.secondary-button { background: #fff; color: #1677ff; border: 2rpx solid #1677ff; border-radius: 16rpx; }
.danger-button { background: #fff; color: #d4380d; border: 2rpx solid #ffccc7; border-radius: 16rpx; }
.error-text { color: #c41d7f; line-height: 1.6; }
.request-id { color: #646a73; font-size: 24rpx; user-select: text; }
button[disabled] { opacity: .55; }
```

Page-specific files may extend these classes without redefining conflicting global values.

- [ ] **Step 5: Verify references, then delete the sample files**

Run before deletion:

```powershell
rg -n "pages/index/index|pages/logs/logs|utils/util|formatTime" account-book-miniapp
```

Expected: references exist only in quickstart files or the old `app.json`. After updating `app.json`, delete the four `pages/index` files and delete `utils/util.ts` only if no live source imports it. Do not delete `typings`, project configuration, package metadata, or `.gitkeep` without a separate reference check.

- [ ] **Step 6: Run configuration, full, and type checks**

```powershell
npm test -- tests/config/app-config.test.ts
npm test
npm run typecheck
git diff --check -- account-book-miniapp
```

Expected: all commands exit 0 and the configuration test proves every configured page exists.

---

### Task 8: Perform Full Verification and Update Project Documentation

**Files:**

- Create: `docs/10-微信小程序首个纵向切片实施记录.md`
- Modify: `docs/07-微信共享记账小程序最终开发执行计划.md`
- Modify: `README.md`
- Verify only: `account-book-server/**`

**Interfaces:**

- Consumes: all Task 5 implementation and tests.
- Produces: reproducible implementation record and truthful Task 5 acceptance status.

- [ ] **Step 1: Reinstall from the lock file and run the complete miniapp gate**

```powershell
Set-Location -LiteralPath '.\account-book-miniapp'
npm ci
npm test
npm run typecheck
npm audit
```

Expected: install succeeds under Node.js 24.20.0, all tests pass, TypeScript exits 0, and the audit reports zero known vulnerabilities. If the registry reports a vulnerability, record the exact package and advisory before changing versions; do not use `npm audit fix --force`.

- [ ] **Step 2: Verify project JSON and forbidden content**

```powershell
node -e "JSON.parse(require('fs').readFileSync('project.config.json','utf8')); JSON.parse(require('fs').readFileSync('miniprogram/app.json','utf8')); console.log('JSON_OK')"
rg -n "AppSecret|session_key|bootstrapKey.*setStorage|console\.(log|debug|info).*code|Authorization.*console" miniprogram tests
```

Expected: `JSON_OK`; the sensitive-pattern scan returns no unsafe logging or persistence. Legitimate request type/property declarations are reviewed manually and not mistaken for secret values.

- [ ] **Step 3: Run the unchanged backend regression gate**

```powershell
Set-Location -LiteralPath '..\account-book-server'
.\mvnw.cmd clean verify
```

Expected: Maven exits 0. Database-gated tests may skip when their explicit isolated-test variables are absent; production database access remains forbidden.

- [ ] **Step 4: Run repository integrity checks**

```powershell
Set-Location -LiteralPath '..'
git diff --check
git status --short --branch
git diff -- account-book-miniapp docs README.md
```

Expected: no whitespace errors; branch remains `codex/mvp-development`; Task 1～4 changes remain present; no commit or push occurs.

- [ ] **Step 5: Perform WeChat Developer Tools verification**

With the local Spring Boot test/development service on `127.0.0.1:8080` and Developer Tools domain validation temporarily disabled, verify:

1. First launch renders the login loading state without logging a WeChat code.
2. `NEED_BOOTSTRAP` navigates to the bootstrap page.
3. Wrong bootstrap key shows a safe error and request ID.
4. Correct bootstrap key reaches home with real user and ledger data in a disposable development environment only.
5. Restarting the simulator retains only `mytallybook.session.v1` and restores the session.
6. Profile changes nickname; no avatar chooser is present.
7. Logout removes the session key and returns to login.
8. Console has no unhandled exception or secret; Network shows `X-Request-Id` and authenticated requests show a Bearer header; do not copy the header value into documentation.
9. Trial/release simulation without a configured domain shows `API_DOMAIN_NOT_CONFIGURED` and makes no request to an IP or port `7631`.

If the available local data cannot safely exercise owner bootstrap, mark only that manual item as pending and do not connect to production to force it.

- [ ] **Step 6: Write the implementation record**

`docs/10-微信小程序首个纵向切片实施记录.md` must record:

- implemented file groups and responsibilities;
- exact Node/npm/TypeScript/Vitest versions actually used;
- automated test counts and command results;
- Developer Tools checks actually completed versus pending;
- Storage key and non-secret field names;
- local/experience/release API environment behavior;
- confirmation that profile is nickname-only;
- confirmation that server, production database, Nginx, systemd, firewall, and ports were unchanged;
- remaining Task 6 boundary for invitations.

Do not include AppSecret, database credentials, initialization key, raw Token, WeChat code, openid, `session_key`, Authorization value, or screenshots containing those values.

- [ ] **Step 7: Update the master plan and README truthfully**

In `docs/07-微信共享记账小程序最终开发执行计划.md`, check Task 5 items only when the corresponding automated or manual evidence exists. Replace its Acceptance paragraph with measured test counts and clearly name any Developer Tools step that still requires the project owner.

In `README.md`, add the miniapp commands:

```powershell
Set-Location -LiteralPath '.\account-book-miniapp'
npm ci
npm test
npm run typecheck
```

Link the design, implementation plan, and implementation record using repository-relative Markdown links.

- [ ] **Step 8: Run final verification after documentation changes**

```powershell
Set-Location -LiteralPath '.\account-book-miniapp'
npm test
npm run typecheck
Set-Location -LiteralPath '..'
git diff --check
git status --short --branch
```

Expected: all executable checks pass, documentation contains no secret values, and all work remains uncommitted on `codex/mvp-development`.

---

## Execution Checkpoints

- Checkpoint A after Task 2: environment and session persistence are independently verified.
- Checkpoint B after Task 4: network, API, and authentication orchestration are independently verified without UI.
- Checkpoint C after Task 7: all four pages compile and the quickstart shell is gone.
- Checkpoint D after Task 8: automated gates, manual Developer Tools evidence, and Markdown records agree.

## Dependency References

- Vitest 4.1.11 package and exact published version: <https://www.npmjs.com/package/vitest>
- Vitest official installation and Node compatibility guidance: <https://vitest.dev/guide/>
- Node.js type definitions 24.3.0: <https://www.npmjs.com/package/@types/node>
