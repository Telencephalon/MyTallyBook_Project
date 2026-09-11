# Task 10：交互、隐私与真机质量实施计划

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 在不改变 Task 1–9 后端业务语义的前提下，补齐小程序导航、设置/关于/隐私入口、统一错误恢复、环境地址分层和双设备验收材料。

**Architecture:** 继续使用原生微信小程序 TypeScript，不引入第三方 UI 框架。将 tabBar 与页面栈导航的选择集中在 app.json 和一个导航辅助模块，将 trial/release 地址校验集中在 config/env.ts，将静态说明页做成无网络依赖页面；已有 runtime、SessionStore、HttpClient、幂等键和乐观锁保持为唯一运行边界。

**Tech Stack:** 微信原生小程序、TypeScript、Vitest、Node.js 24、WXML/WXSS、已有 Nginx 模板；使用本机微信开发者工具自带 wcc.exe 做原生 WXML 编译。

**Spec:** docs/superpowers/specs/2026-09-09-task10-interaction-privacy-device-design.md

## Global Constraints

- 所有工作、测试和启动只使用 D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project。
- 不创建、读取或同步退休的 MyTallyBook_Project-worktrees；不删除归档目录。
- 不修改数据库、迁移、AppSecret、数据库密码、APP_BOOTSTRAP_KEY 或 Pepper。
- 不把 117.72.101.42:7631 写成 trial/release 合法 API 地址；开发地址仍只服务于开发者工具。
- trial 与 release 只接受 HTTPS 主机地址；缺失、IP、localhost、带不安全协议的地址必须在请求前返回配置错误。
- 不新增位置、相册、摄像头、麦克风、手机号、剪贴板读取或通讯录权限。
- 写请求不得添加未知结果自动重试；保留 clientRequestId 幂等、version 乐观锁和 401 清会话流程。
- 每个生产行为改动遵循 RED → GREEN → 回归；不得删除失败断言或把失败显示成空成功。
- 不执行生产部署、真实 MySQL 清理、微信上传或审核；Task 10 完成后由用户另行决定运行包更新。
- 保留所有现有未提交变更；本仓库约束不授权 Git commit、push、reset、clean 或 merge。

---

### Task 1: 环境地址分层与会话隔离

Files:
- Modify: account-book-miniapp/miniprogram/config/env.ts
- Modify: account-book-miniapp/tests/config/env.test.ts
- Modify: account-book-miniapp/miniprogram/store/session.ts
- Modify: account-book-miniapp/miniprogram/runtime.ts
- Modify: account-book-miniapp/tests/store/session.test.ts
- Create: account-book-miniapp/tests/runtime-environment.test.ts

Interfaces:
- env.ts produces resolveApiEnvironment(envVersion, addresses) and getApiEnvironment(); addresses contains trialBaseUrl and releaseBaseUrl, both defaulting to empty strings.
- SessionStore constructor accepts an environmentId as its third argument; defaults to develop|http://127.0.0.1:7631 for existing local test call sites. PersistedSession adds environmentId. hydrate rejects/removes legacy or mismatched records. createSessionStore(environmentId) uses the private existing wx storage adapter. runtime resolves envVersion once, validates the environment, then creates its SessionStore with envVersion + '|' + normalized baseUrl. No in-app address editing is introduced.

- [ ] Step 1: Add RED tests for trial/release selecting separate addresses; reject empty/http/IP/localhost, URL credentials, path/query/fragment, unknown envVersion and alternate IP spellings. Preserve develop loopback. Prove that different environmentId or legacy persisted records cannot restore a token; matching environments do restore.
- [ ] Step 2: Run from account-book-miniapp: npm test -- tests/config/env.test.ts tests/store/session.test.ts tests/runtime-environment.test.ts. Confirm failures identify missing address separation or token-origin protection.
- [ ] Step 3: Implement the smallest address model and validation in config/env.ts; keep the two HTTPS values as explicit configuration placeholders and never read secrets from this file.
- [ ] Step 4: Bind persisted sessions to environmentId; keep the existing storage key and add an explicit field check. Legacy records lacking the field are cleared without reuse, requiring one login. Instantiate runtime/session only after validation, so invalid release config cannot send a development credential. Test in-memory continuation guards remain valid.
- [ ] Step 5: Run the focused tests and then npm run typecheck. Expected result: all focused tests and both TypeScript projects pass.

### Task 2: Settings, About and Privacy pages

Files:
- Create: account-book-miniapp/miniprogram/pages/settings/index.ts
- Create: account-book-miniapp/miniprogram/pages/settings/index.wxml
- Create: account-book-miniapp/miniprogram/pages/settings/index.wxss
- Create: account-book-miniapp/miniprogram/pages/settings/index.json
- Create: account-book-miniapp/miniprogram/pages/about/index.ts
- Create: account-book-miniapp/miniprogram/pages/about/index.wxml
- Create: account-book-miniapp/miniprogram/pages/about/index.wxss
- Create: account-book-miniapp/miniprogram/pages/about/index.json
- Create: account-book-miniapp/miniprogram/pages/privacy/index.ts
- Create: account-book-miniapp/miniprogram/pages/privacy/index.wxml
- Create: account-book-miniapp/miniprogram/pages/privacy/index.wxss
- Create: account-book-miniapp/miniprogram/pages/privacy/index.json
- Create: account-book-miniapp/tests/settings-pages.spec.ts
- Create: account-book-miniapp/tests/privacy-pages.spec.ts
- Create: account-book-miniapp/miniprogram/config/app-info.ts
- Modify: account-book-miniapp/miniprogram/app.json
- Modify: account-book-miniapp/miniprogram/pages/login/index.ts
- Modify: account-book-miniapp/miniprogram/pages/login/index.wxml
- Modify: account-book-miniapp/tests/config/app-config.test.ts

Interfaces:
- Settings page is the future “我的” root and consumes getRuntime().flow.refreshContext()/logout(), session user/ledger and roleLabel; exposes profile, members, invite management (OWNER/ADMIN only), category/account, about/privacy and logout handlers. No fabricated preference switches or account-deletion APIs.
- app-info.ts exports APP_INFO with appName '随手账', version from the existing package version, operator/contact/filingNumber empty, filingStatus '备案中', and filingQueryUrl 'https://beian.miit.gov.cn'. Empty deployment values are intentional product state, not missing implementation. Privacy text explains identifier/session/shared-ledger/audit processing; logout does not delete shared data. Do not add optional avatar collection or imply encrypted clipboard storage.
- About and Privacy pages are static and expose no API calls, credential fields, clipboard reads, or platform permission requests.

- [ ] Step 1: Write RED tests asserting settings links, logout busy guard, logout 401 handling, and static About/Privacy text for actual collected data categories without secret values.
- [ ] Step 2: Run the two focused test files and confirm they fail because pages do not exist.
- [ ] Step 3: Add the three four-file page units using existing page styles and toErrorView; disable repeated logout taps and route successful logout to pages/login/index.
- [ ] Step 4: Add app.json page registrations and route metadata; keep all copy truthful when备案号、运营方或联系方式配置为空 by showing “待配置/备案中”.
- [ ] Step 4a: Add public About/Privacy entry links on login without getRuntime dependency; guard active login completions so reading static pages does not get interrupted. About filing query is a selectable URL with explicitly clicked copy, no automatic external web-view or clipboard access. Update the actual clipboard use inventory accordingly.
- [ ] Step 5: Run focused tests, TypeScript checks, and inspect the generated page list for exactly four artifacts per page.

### Task 3: Migrate existing routes to the tab/stack contract

Files:
- Modify: account-book-miniapp/miniprogram/pages/home/index.ts
- Modify: account-book-miniapp/miniprogram/pages/home/index.wxml
- Modify: account-book-miniapp/miniprogram/pages/profile/index.ts
- Modify: account-book-miniapp/miniprogram/pages/profile/index.wxml
- Modify: account-book-miniapp/miniprogram/pages/entry-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/statistics/index.ts
- Modify: account-book-miniapp/miniprogram/pages/category-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/account-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/member-list/index.ts
- Modify: account-book-miniapp/tests/home-bookkeeping.spec.ts
- Create: account-book-miniapp/tests/navigation.test.ts
- Create: account-book-miniapp/miniprogram/utils/navigation.ts
- Modify: account-book-miniapp/miniprogram/app.json
- Modify: account-book-miniapp/tests/config/app-config.test.ts
- Modify: account-book-miniapp/miniprogram/pages/entry-detail/index.ts
- Modify: account-book-miniapp/miniprogram/app.wxss

Interfaces:
- navigation.ts exports TAB_ROUTES, navigateToPage(url: string): void and goHome(): void. navigateToPage selects wx.switchTab for known tab paths, otherwise wx.navigateTo. goHome switches to /pages/home/index. Existing login/bootstrap/invite reLaunch behavior can remain where it intentionally clears the stack; do not forbid a supported reLaunch to tab page.

- [ ] Step 1: Add RED route assertions for Home entries/statistics/settings and Settings profile/About/Privacy links; assert tab URLs use wx.switchTab and stack URLs use wx.navigateTo. Profile remains an editor stack page and its successful save navigates back.
- [ ] Step 2: Run navigation and affected page tests and verify the old direct tab navigate calls fail the assertions.
- [ ] Step 3: Configure native tabBar for home, entry-list, statistics, settings, after all page artifacts exist. Replace only affected tab-page navigation with navigateToPage; specifically change entry-detail soft-delete redirect to the entry-list tab. Keep detail/edit/create/category/account/member/invite routes on stack navigation and preserve permission guards. Clear cached sensitive tab data on session loss before an old request could expose it. Add bottom safe-area padding for non-tab form/action pages, and usable min-height/line-height for inputs and textareas; preserve scroll reachability with the keyboard open.
- [ ] Step 4: Add the Settings entry under the existing profile/home actions without changing role checks for member and invite management.
- [ ] Step 5: Run the focused route/page tests and full miniapp tests; inspect for stale paths with rg against all miniprogram TypeScript/WXML files.

### Task 4: Complete cross-page error and form recovery behavior

Files:
- Modify: account-book-miniapp/miniprogram/utils/presentation.ts
- Modify: account-book-miniapp/miniprogram/pages/login/index.ts
- Modify: account-book-miniapp/miniprogram/pages/bootstrap/index.ts
- Modify: account-book-miniapp/miniprogram/pages/invite-accept/index.ts
- Modify: account-book-miniapp/miniprogram/pages/member-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/member-edit/index.ts
- Modify: account-book-miniapp/miniprogram/pages/category-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/category-edit/index.ts
- Modify: account-book-miniapp/miniprogram/pages/account-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/account-edit/index.ts
- Modify: account-book-miniapp/miniprogram/pages/entry-list/index.ts
- Modify: account-book-miniapp/miniprogram/pages/entry-create/index.ts
- Modify: account-book-miniapp/miniprogram/pages/entry-detail/index.ts
- Modify: account-book-miniapp/miniprogram/pages/entry-edit/index.ts
- Modify: account-book-miniapp/miniprogram/pages/statistics/index.ts
- Modify: account-book-miniapp/tests/entry-pages.spec.ts
- Modify: account-book-miniapp/tests/pages/catalog-pages.test.ts
- Modify: account-book-miniapp/tests/pages/invite-member-pages.test.ts
- Modify: account-book-miniapp/tests/pages/session-final-review.test.ts
- Modify: account-book-miniapp/tests/statistics-pages.spec.ts
- Modify: account-book-miniapp/tests/utils/presentation.test.ts
- Modify as required by failing rendered-state tests: account-book-miniapp/miniprogram/pages/login/index.wxml; pages/bootstrap/index.wxml; pages/invite-accept/index.wxml; pages/member-list/index.wxml; pages/member-edit/index.wxml; pages/category-list/index.wxml; pages/category-edit/index.wxml; pages/account-list/index.wxml; pages/account-edit/index.wxml; pages/entry-list/index.wxml; pages/entry-create/index.wxml; pages/entry-detail/index.wxml; pages/entry-edit/index.wxml; pages/statistics/index.wxml (all page paths use account-book-miniapp/miniprogram/ as their root).

- [ ] Step 1: Write RED cases for network failure preserving entry-create/edit form data, disabled submit during an in-flight operation, 401 relaunch, invite expired/full, forbidden action, and version conflict recovery.
- [ ] Step 2: Run only those cases and confirm each failure is behavioral rather than a test setup error.
- [ ] Step 3: Reuse existing AppError/toErrorView and page generation guards to add the smallest missing states; never auto-retry unknown write results and never clear valid data on an unrelated section failure.
- [ ] Step 4: Run focused cases, then all current Task 7–9 tests to prevent regression of idempotency, conflict, and statistics recovery.

### Task 5: Privacy copy, static route audit, and configuration documentation

Files:
- Modify: docs/20-交互隐私真机与后续部署准备记录.md
- Modify: README.md
- Modify: docs/07-微信共享记账小程序最终开发执行计划.md
- Modify: docs/18-收支记账与首页统计实施记录.md
- Add evidence under: .superpowers/sdd/2026-09-09-task10-interaction-privacy/

- [ ] Step 1: Extend tests/config/app-config.test.ts and tests/navigation.test.ts to read app.json, verify every registered page has ts/wxml/wxss/json and tab metadata matches TAB_ROUTES, and exercise each migrated Page handler. Use tests/config/env.test.ts for trial/release IP rejection. Static source scanning is supplementary, not a substitute for handler behavior assertions.
- [ ] Step 2: Run the audit before documentation changes and retain the failing output if the new page registration is incomplete.
- [ ] Step 3: Update the privacy copy and records from the final static code call inventory; record only actual APIs and leave备案、临时域名、联系方式 as explicit configuration gates.
- [ ] Step 4: Run the audit again and confirm no stale statement says Task 10 or真机 acceptance is complete before physical testing.

### Task 6: Offline and native verification gate

Files:
- No production source additions; update the Task 10 evidence ledger and implementation record only.

- [ ] Step 1: Run npm test in account-book-miniapp and require all test files and tests to pass with zero failures.
- [ ] Step 2: Run npm run typecheck and require both tsconfig.json and tsconfig.test.json to exit 0.
- [ ] Step 3: Compile every app.json page with the checked absolute wcc.exe output path inside the main project; require exit 0 and four files for every page.
- [ ] Step 4: Run git diff --check and an rg route audit; record counts, compiler bytes, config hash checks, and protected credential/JAR state without printing secrets.
- [ ] Step 5: Request independent review of the Task 10 diff. Do not update the backend or perform database cleanup solely because offline checks pass.

### Task 7: Physical acceptance handoff

Files:
- Create: docs/21-Task10双设备验收清单.md
- Modify: docs/20-交互隐私真机与后续部署准备记录.md

- [ ] Step 1: Prepare two copies of the checklist for Mate 70 Pro and iPhone 15 with fields for OS, WeChat, base library, build, network, timestamp, result, screenshot path, and request ID.
- [ ] Step 2: Give the user the exact simulator/real-device steps; do not claim success from a screenshot or simulator alone.
- [ ] Step 3: Record each completed flow and separate Android, iOS, and HarmonyOS coverage. If the Mate 70 Pro is HarmonyOS NEXT, keep that label and do not count it as Android.
- [ ] Step 4: Mark Task 10 complete only after the user returns both device records and all required flows pass; otherwise leave the specific failed gate open.

## Completion criteria

## Concrete test/implementation anchors

These snippets define the consumer contracts for the steps above. Use the existing Vitest Page capture pattern (as in statistics-pages.spec.ts) with wx and runtime as external boundaries; exercise real handlers and assert visible data, not merely that a mock returns a configured value.

### Task 1 RED and GREEN anchors

    // tests/config/env.test.ts
    it('keeps trial and release hosts separate', () => {
      const addresses = { trialBaseUrl: 'https://trial.example.com', releaseBaseUrl: 'https://api.example.com' }
      expect(resolveApiEnvironment('trial', addresses).baseUrl).toBe(addresses.trialBaseUrl)
      expect(resolveApiEnvironment('release', addresses).baseUrl).toBe(addresses.releaseBaseUrl)
      expect(() => resolveApiEnvironment('release', { ...addresses, releaseBaseUrl: '' })).toThrow()
    })

    // tests/store/session.test.ts: reuse the existing valid AUTHENTICATED fixture
    const storage = new Map<string, unknown>()
    const adapter = { get: (key: string) => storage.get(key), set: (key: string, value: unknown) => { storage.set(key, value) }, remove: (key: string) => { storage.delete(key) } }
    const trial = new SessionStore(adapter, () => NOW, 'trial|https://trial.example.com')
    trial.saveAuthenticated(validAuth)
    const release = new SessionStore(adapter, () => NOW, 'release|https://api.example.com')
    expect(release.hydrate()).toBe(false)
    expect(release.getToken()).toBeNull()

Production change anchors (alongside existing validation, do not replace it):

    export interface ApiAddresses { trialBaseUrl: string; releaseBaseUrl: string }
    const API_ADDRESSES: ApiAddresses = { trialBaseUrl: '', releaseBaseUrl: '' }
    // resolveApiEnvironment keeps ApiEnvironment { baseUrl, timeoutMs } unchanged.
    // PersistedSession and saveAuthenticated both add the exact environmentId field.
    if (candidate.environmentId !== this.environmentId) return false

runtime must obtain the build version and validated base URL before constructing the HttpClient:

    const envVersion = wx.getAccountInfoSync().miniProgram.envVersion
    const environment = resolveApiEnvironment(envVersion)
    const session = createSessionStore(envVersion + '|' + environment.baseUrl.replace(/:443$/, ''))
    // Use this one local session in HttpClient.getToken/onUnauthorized and every Flow.

Do not retain the old exported globally unscoped singleton as a second runtime session. The factory uses the existing private wx StorageAdapter; test constructors remain dependency-injected.

### Task 2 public-page and logout anchors

    // In a captured real about/privacy Page, runtime import must not be required.
    expect(page.data.appName).toBe('随手账')
    expect(page.data.filingText).toBe('备案中')
    expect(page.data.contactText).toBe('待配置')
    expect(wx.request).not.toHaveBeenCalled()
    // Static WXML must include identifier, session, shared-ledger and audit sections.

    // Settings Page with a pending external logout request:
    const first = page.logout()
    await page.logout()
    expect(page.data.loggingOut).toBe(true)
    pendingLogout.reject(new AppError('NETWORK', 'NETWORK_ERROR', '网络连接失败'))
    await first
    expect(page.data.loggingOut).toBe(false)
    expect(page.data.errorMessage).toBe('网络连接失败')
    expect(runtime.session.getToken()).toBe(originalToken)

Use the existing Home.logout semantics for CLEARED / ALREADY_HANDLED / SUPERSEDED. Do not add a fresh independently defined logout success rule. The concrete Settings handler names are openProfile, openMembers, openInvites, openCategories, openAccounts, openAbout, openPrivacy, logout. Its state uses loading, loggingOut, errorMessage, requestId, nickname, roleLabel, ledgerName, canManageInvites.

### Task 3 route contract and implementation

    export const TAB_ROUTES = [
      '/pages/home/index', '/pages/entry-list/index',
      '/pages/statistics/index', '/pages/settings/index',
    ] as const
    export function navigateToPage(url: string): void {
      if ((TAB_ROUTES as readonly string[]).includes(url)) {
        wx.switchTab({ url })
      } else {
        wx.navigateTo({ url })
      }
    }
    export function goHome(): void { navigateToPage('/pages/home/index') }

    navigateToPage('/pages/entry-list/index')
    expect(wx.switchTab).toHaveBeenCalledWith({ url: '/pages/entry-list/index' })
    navigateToPage('/pages/profile/index')
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/profile/index' })

Also invoke Home.openEntries/openStatistics and the real entry-detail delete continuation to prove migrated consumers use the new contract. app.json tab list must be exactly home/entry-list/statistics/settings, with texts 首页/明细/统计/我的; existing profile stays in pages but outside tabBar. Initial native tabBar can use text labels without adding image-generation dependencies.

Safe-area baseline, applied only to the appropriate page container:

    .page { padding-bottom: calc(32rpx + env(safe-area-inset-bottom)); }
    input { min-height: 80rpx; line-height: 1.5; }

Do not set fixed viewport heights that prevent scrolling; validate keyboard overlap and large text on actual devices separately.

### Task 4 error-state behavioral assertions

    // Reuse entry-pages.spec.ts's real page/runtime harness and payload fixtures.
    const before = { amount: page.data.amount, note: page.data.note, categoryId: page.data.categoryId }
    runtime.entries.create.mockRejectedValueOnce(new AppError('NETWORK', 'NETWORK_ERROR', '网络连接失败'))
    await page.onSubmit()
    expect(page.data.amount).toBe(before.amount)
    expect(page.data.note).toBe(before.note)
    expect(page.data.categoryId).toBe(before.categoryId)
    expect(page.data.errorMessage).not.toBe('')

Add cases through the existing real flow API names before changing production code. For every gap, record the failing case and fix only that gap; if an existing case already passes, retain it as regression evidence rather than rewriting working behavior. A configuration error from getRuntime must be caught at an entry boundary and shown without attempting wx.request. Do not weaken safe toErrorView behavior: unknown exceptions stay generic and no secret/raw stack appears.

### Tasks 5–7 exact verification commands

From account-book-miniapp:

    npm.cmd test
    npm.cmd run typecheck

From account-book-miniapp/miniprogram, PowerShell:

    $root = 'D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project'
    $outDir = Join-Path $root '.superpowers/sdd/2026-09-09-task10-interaction-privacy'
    $null = New-Item -ItemType Directory -Path $outDir -Force
    $outputPath = [IO.Path]::GetFullPath((Join-Path $outDir 'task10-wxml.js'))
    if (-not $outputPath.StartsWith([IO.Path]::GetFullPath($root) + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'OutputOutsideMain' }
    $config = Get-Content -LiteralPath app.json -Raw | ConvertFrom-Json
    $inputs = @($config.pages | ForEach-Object { $_ + '.wxml' })
    & 'D:/Work/Tools/WeChatWebDevelop/微信web开发者工具/code/package.nw/node_modules/wcc-exec/wcc.exe' -o $outputPath @inputs
    if ($LASTEXITCODE -ne 0) { throw 'NativeWxmlFailed' }

From the main repository:

    git diff --check
    rg -n 'navigateTo|redirectTo|switchTab|reLaunch' account-book-miniapp/miniprogram -g '*.ts'
    rg -n 'wx\.|open-type=|type="nickname"' account-book-miniapp/miniprogram -g '*.ts' -g '*.wxml'

The route and privacy scans are evidence inventories, not success-by-exit-code tests. Investigate each match against the registered tab list and actual permission inventory.

Device checklist rows: launch/login/restore, invitation accept/manage, role denial, category/account, expense/income, edit/version conflict, soft-delete, month/direction statistics, network failure + original-input retry, logout/restore, keyboard/safe-area/large-font. Each row has 未执行/通过/失败, device/OS/WeChat/base-library/build/network, timestamp, requestId and redacted screenshot path. No passwords, tokens, notes or raw invite codes in evidence.

Task 10 is complete only when Tasks 1–6 pass, the independent review has no Critical or Important issue, both devices have returned the required evidence, and the documentation distinguishes code, offline, device, domain, and deployment status. The temporary domain remains a configuration gate until the user supplies it and the HTTPS/Nginx/WeChat legal-domain conditions are separately verified.

## 2026-09-10 用户授权的整体复核修复补充

Task6 自动验证通过、整体复核未通过后，用户明确允许修复报告中的六类问题并重新验证。因此本次补充授权覆盖必要的小程序页面逻辑、模板、样式和回归测试，不再受 Task6 初始“仅证据文件”范围限制；其余 Global Constraints 不变，仍不执行后端/数据库/服务/密钥或 Task7。

采用同一子代理的一次修复波次，按“失败测试→最小修复→定向回归”依次完成：

- [ ] pending 写操作锁跨前后台保持，settle 后正确释放，不应用旧 UI 回调。
- [ ] 成员加载/失败/finally 统一代次与身份隔离。
- [ ] 邀请错误态与空成功态互斥。
- [ ] 新增记账字典读重试与写 intent 重试完全分离。
- [ ] 复现并保护初次读取失败后的编辑草稿，保留既有已加载资源的 dirty guard。
- [ ] 补齐底部安全区和 input/textarea 尺寸基线，仍待真机确认。
- [ ] 全量测试、双类型检查、20 页原生编译、保护态对比和修复范围独立复核。

具体文件、接口、测试场景、代码/命令锚点与取证要求以本计划目录中的 [修复执行说明](../../../.superpowers/sdd/2026-09-09-task10-interaction-privacy-device-implementation/task-6-fix1-brief.md) 为准。修复只处理全量复核六项及修复引入的问题；非阻断观察和运行时/真机条件风险不得偷偷扩大为新功能或已验收事实。
