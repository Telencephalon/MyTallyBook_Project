import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'

type PageShape = Record<string, any> & {
  data: Record<string, any>
  setData(update: object): void
}

type PageName = 'category-list' | 'account-list' | 'category-edit' | 'account-edit'

const category = {
  id: 7,
  entryType: 'EXPENSE',
  name: '餐饮',
  icon: null,
  color: null,
  sortNo: 0,
  systemDefault: false,
  status: 'ACTIVE',
}

const account = {
  id: 7,
  name: '现金',
  accountType: 'CASH',
  initialBalance: '1.00',
  currentBalance: '2.00',
  sortNo: 0,
  status: 'ACTIVE',
  version: 2,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function runtimeFor() {
  let revision = 1
  let token = 'session-a'
  return {
    session: {
      getRevision: () => revision,
      getToken: () => token,
      getUser: () => ({ userId: 1, role: 'OWNER' }),
    },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    catalog: {
      categories: vi.fn().mockResolvedValue({ items: [category] }),
      category: vi.fn().mockResolvedValue(category),
      createCategory: vi.fn().mockResolvedValue(category),
      updateCategory: vi.fn().mockResolvedValue(category),
      deleteCategory: vi.fn().mockResolvedValue(undefined),
      accounts: vi.fn().mockResolvedValue({ items: [account] }),
      account: vi.fn().mockResolvedValue(account),
      createAccount: vi.fn().mockResolvedValue(account),
      updateAccount: vi.fn().mockResolvedValue(account),
      deleteAccount: vi.fn().mockResolvedValue(undefined),
    },
    setRevision(value: number) {
      revision = value
    },
    setToken(value: string) {
      token = value
    },
  }
}

async function loadPage(name: PageName, runtime: Record<string, any>, confirm = true) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => {
    definition = value
  })
  vi.stubGlobal('wx', {
    navigateTo: vi.fn(),
    navigateBack: vi.fn(),
    redirectTo: vi.fn(),
    showModal: vi.fn((options: any) => options.success({ confirm, cancel: !confirm })),
    showToast: vi.fn(),
  })
  vi.doMock('../../miniprogram/runtime', () => ({ getRuntime: () => runtime }))

  switch (name) {
    case 'category-list':
      await import('../../miniprogram/pages/category-list/index')
      break
    case 'account-list':
      await import('../../miniprogram/pages/account-list/index')
      break
    case 'category-edit':
      await import('../../miniprogram/pages/category-edit/index')
      break
    case 'account-edit':
      await import('../../miniprogram/pages/account-edit/index')
      break
  }

  return {
    ...definition!,
    data: structuredClone(definition!.data),
    setData(this: PageShape, update: object) {
      Object.assign(this.data, update)
    },
  } as PageShape
}

afterEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('catalog list pages', () => {
  it('defines the shared safe-area and form control sizing baseline', () => {
    const appWxss = readFileSync(new URL('../../miniprogram/app.wxss', import.meta.url), 'utf8')
    expect(appWxss).toContain('padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));')
    expect(appWxss).toMatch(/input,\s*textarea\s*\{[^}]*min-height:\s*80rpx/)
    expect(appWxss).toMatch(/textarea\s*\{[^}]*min-height:\s*160rpx/)
    expect(appWxss).toMatch(/line-height:\s*1\.5/)
  })

  it('renders a retry action for category read failures and retries the list', async () => {
    const runtime = runtimeFor()
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage('category-list', runtime)

    await page.onShow()

    expect(page.data.errorMessage).not.toBe('')
    expect(typeof page.retry).toBe('function')
    const wxml = readFileSync(new URL('../../miniprogram/pages/category-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="retry"')

    await page.retry()
    expect(runtime.flow.refreshContext).toHaveBeenCalledTimes(2)
  })

  it('renders a retry action for account read failures and retries the list', async () => {
    const runtime = runtimeFor()
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage('account-list', runtime)

    await page.onShow()

    expect(page.data.errorMessage).not.toBe('')
    expect(typeof page.retry).toBe('function')
    const wxml = readFileSync(new URL('../../miniprogram/pages/account-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="retry"')

    await page.retry()
    expect(runtime.flow.refreshContext).toHaveBeenCalledTimes(2)
  })

  it('does not render a member-list empty state while an error is visible', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/member-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toMatch(/!items\.length[^>]*!errorMessage/)
  })

  it('renders request identifiers for account-list failures', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/account-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('请求编号：{{requestId}}')
  })

  it('renders request identifiers for entry-edit conflict failures', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/entry-edit/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('请求编号：{{requestId}}')
  })

  it('renders request identifiers for entry-detail failures', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/entry-detail/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('请求编号：{{requestId}}')
  })

  it('renders request identifiers for category-edit failures', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/category-edit/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('请求编号：{{requestId}}')
  })

  it('member-edit fails closed when runtime initialization is unavailable', async () => {
    let definition: PageShape | undefined
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { request: vi.fn() })
    vi.doMock('../../miniprogram/runtime', () => ({
      getRuntime: () => { throw new Error('configuration unavailable') },
    }))
    await import('../../miniprogram/pages/member-edit/index')
    const page = {
      ...definition!, data: structuredClone(definition!.data),
      setData(this: PageShape, update: object) { Object.assign(this.data, update) },
    } as PageShape

    page.onLoad({ memberId: '22' })
    await expect(page.onShow()).resolves.toBeUndefined()
    expect(page.data.errorMessage).toBe('操作失败，请稍后重试')
    expect(page.data.canChangeRole).toBe(false)
    expect(page.data.canRemove).toBe(false)
    expect(page.data.canTransfer).toBe(false)
  })

  it('keeps the category list read-only for a member', async () => {
    const runtime = runtimeFor()
    runtime.session.getUser = () => ({ userId: 1, role: 'MEMBER' })
    const page = await loadPage('category-list', runtime)

    await page.onShow()
    page.openCreate()

    expect(page.data.items).toHaveLength(1)
    expect(page.data.canManage).toBe(false)
    expect(wx.navigateTo).not.toHaveBeenCalled()
  })

  it.each([
    ['category-list', 'deleteCategory', 'categories'],
    ['account-list', 'deleteAccount', 'accounts'],
  ] as const)('%s clears busy and refreshes after successful deletion', async (name, remove, list) => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    await page.onShow()

    await page.onDelete({ currentTarget: { dataset: { id: 7 } } })

    expect(runtime.catalog[remove]).toHaveBeenCalledTimes(1)
    expect(runtime.catalog[list]).toHaveBeenCalledTimes(2)
    expect(page.data.busy).toBe(false)
    expect(page.data.loading).toBe(false)
  })

  it.each([
    ['category-list', 'deleteCategory'],
    ['account-list', 'deleteAccount'],
  ] as const)('%s clears busy when deletion confirmation is cancelled', async (name, remove) => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime, false)
    await page.onShow()

    await page.onDelete({ currentTarget: { dataset: { id: 7 } } })

    expect(runtime.catalog[remove]).not.toHaveBeenCalled()
    expect(page.data.busy).toBe(false)
  })

  it.each([
    ['category-list', 'deleteCategory'],
    ['account-list', 'deleteAccount'],
  ] as const)('%s keeps a hidden pending deletion locked until it settles', async (name, remove) => {
    const pendingDelete = deferred<void>()
    const runtime = runtimeFor()
    runtime.catalog[remove].mockReturnValueOnce(pendingDelete.promise)
    const page = await loadPage(name, runtime)
    await page.onShow()

    const pending = page.onDelete({ currentTarget: { dataset: { id: 7 } } })
    await Promise.resolve()
    await Promise.resolve()
    page.onHide()
    await page.onShow()
    await page.onDelete({ currentTarget: { dataset: { id: 7 } } })
    expect(runtime.catalog[remove]).toHaveBeenCalledTimes(1)
    expect(page.data.busy).toBe(true)
    pendingDelete.resolve()
    await pending

    expect(page.data.busy).toBe(false)
    expect(page.data.loading).toBe(false)
    page.openCreate()
    expect(wx.navigateTo).toHaveBeenCalledTimes(1)
  })

  it('suppresses a category response from an old session', async () => {
    const pendingCategories = deferred<{ items: Array<typeof category> }>()
    const runtime = runtimeFor()
    runtime.catalog.categories.mockReturnValueOnce(pendingCategories.promise)
    const page = await loadPage('category-list', runtime)

    const pending = page.onShow()
    await Promise.resolve()
    runtime.setRevision(2)
    pendingCategories.resolve({ items: [{ ...category, id: 99 }] })
    await pending

    expect(page.data.items).toEqual([])
  })
})

describe('catalog edit pages', () => {
  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s preserves a dirty form across background and return', async (name, read) => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '未保存名称' } })

    page.onHide()
    await page.onShow()

    expect(page.data.name).toBe('未保存名称')
    expect(runtime.catalog[read]).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['category-edit', 'updateCategory'],
    ['account-edit', 'updateAccount'],
  ] as const)('%s keeps a hidden pending save locked until it settles', async (name, update) => {
    const pendingUpdate = deferred<unknown>()
    const runtime = runtimeFor()
    runtime.catalog[update].mockReturnValueOnce(pendingUpdate.promise)
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '未保存名称' } })

    const pending = page.onSubmit()
    page.onHide()
    await page.onShow()
    await page.onSubmit()
    expect(runtime.catalog[update]).toHaveBeenCalledTimes(1)
    expect(page.data.busy).toBe(true)
    pendingUpdate.resolve(undefined)
    await pending

    expect(page.data.busy).toBe(false)
    expect(page.data.loading).toBe(false)
    expect(page.data.name).toBe('未保存名称')
    expect(wx.redirectTo).not.toHaveBeenCalled()
  })

  it('returns to the existing category list after saving an edit', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('category-edit', runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '新分类名称' } })

    await page.onSubmit()

    expect(runtime.catalog.updateCategory).toHaveBeenCalledTimes(1)
    expect(wx.navigateBack).toHaveBeenCalledTimes(1)
    expect(wx.redirectTo).not.toHaveBeenCalled()
  })

  it('retains account input on version conflict and reloads only after confirmation', async () => {
    const runtime = runtimeFor()
    runtime.catalog.updateAccount.mockRejectedValueOnce({
      code: 'RESOURCE_STATE_CHANGED',
      message: '资源状态已变化，请刷新后重试',
    })
    runtime.catalog.account
      .mockResolvedValueOnce(account)
      .mockResolvedValueOnce({ ...account, name: '服务端新名称', version: 3 })
    const page = await loadPage('account-edit', runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: ' 本地未保存名称 ' } })

    await page.onSubmit()

    expect(runtime.catalog.updateAccount).toHaveBeenCalledWith(7, {
      name: '本地未保存名称',
      sortNo: 0,
      status: 'ACTIVE',
      version: 2,
    })
    expect(page.data.name).toBe(' 本地未保存名称 ')
    expect(page.data.canReload).toBe(true)

    await page.onReload()

    expect(page.data.name).toBe('服务端新名称')
    expect(page.data.version).toBe(3)
    expect(page.data.canReload).toBe(false)
  })

  it.each([
    ['category-edit', 'updateCategory', 'category'],
    ['account-edit', 'updateAccount', 'account'],
  ] as const)('%s preserves conflict feedback and reload action across hide/show', async (name, update, read) => {
    const runtime = runtimeFor()
    runtime.catalog[update].mockRejectedValueOnce({
      code: 'RESOURCE_STATE_CHANGED',
      message: '资源状态已变化，请刷新后重试',
    })
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' }); await page.onShow()
    page.onNameInput({ detail: { value: '本地未保存名称' } })
    await page.onSubmit()
    const conflictMessage = page.data.errorMessage

    page.onHide(); await page.onShow()

    expect(conflictMessage).not.toBe('')
    expect(page.data.errorMessage).toBe(conflictMessage)
    expect(page.data.canReload).toBe(true)
    expect(runtime.catalog[read]).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s exposes an explicit retry for a failed initial read', async (name, read) => {
    const runtime = runtimeFor()
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })

    await page.onShow()

    expect(page.data.errorMessage).not.toBe('')
    expect(typeof page.retry).toBe('function')
    const wxml = readFileSync(new URL(`../../miniprogram/pages/${name}/index.wxml`, import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="retry"')

    await page.retry()
    expect(runtime.flow.refreshContext).toHaveBeenCalledTimes(2)
    expect(runtime.catalog[read]).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s preserves input entered after an initial read failure when retry succeeds', async (name, read) => {
    const runtime = runtimeFor()
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })

    await page.onShow()
    page.onNameInput({ detail: { value: '错误态新名称' } })
    await page.retry()

    expect(runtime.catalog[read]).toHaveBeenCalledTimes(1)
    expect(page.data.name).toBe('错误态新名称')
    expect(page.data.canRetryRead).toBe(false)
    expect(page.data.version ?? 0).toBeGreaterThanOrEqual(0)
  })

  it('does not overwrite an account form with a response from an old session', async () => {
    const pendingAccount = deferred<typeof account>()
    const runtime = runtimeFor()
    runtime.catalog.account.mockReturnValueOnce(pendingAccount.promise)
    const page = await loadPage('account-edit', runtime)
    page.onLoad({ id: '7' })

    const pending = page.onShow()
    await Promise.resolve()
    runtime.setRevision(2)
    pendingAccount.resolve({ ...account, name: '旧会话账户' })
    await pending

    expect(page.data.name).toBe('')
  })

  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s does not carry a dirty draft into a changed session', async (name, read) => {
    const runtime = runtimeFor()
    runtime.catalog[read].mockImplementation(async () => (
      runtime.session.getRevision() === 1
        ? { ...(read === 'category' ? category : account), name: 'A 服务端名称' }
        : { ...(read === 'category' ? category : account), name: 'B 服务端名称' }
    ))
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: 'A 身份未保存名称' } })

    runtime.setRevision(2)
    await page.onShow()

    expect(page.data.name).toBe('B 服务端名称')
  })

  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s does not carry a first-read-failure draft into a changed session', async (name, read) => {
    const runtime = runtimeFor()
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    runtime.catalog[read].mockResolvedValueOnce(
      read === 'category' ? { ...category, name: 'B 服务端名称' } : { ...account, name: 'B 服务端名称' },
    )
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })

    await page.onShow()
    page.onNameInput({ detail: { value: 'A 失败态未保存名称' } })
    runtime.setRevision(2)
    await page.retry()

    expect(page.data.name).toBe('B 服务端名称')
  })

  it.each([
    ['category-edit', 'updateCategory'],
    ['account-edit', 'updateAccount'],
  ] as const)('%s refuses to submit an obsolete draft before the next show', async (name, update) => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: 'A 身份未保存名称' } })

    page.onHide()
    runtime.setRevision(2)
    await page.onSubmit()

    expect(runtime.catalog[update]).not.toHaveBeenCalled()
  })

  it.each([
    ['category-edit', 'updateCategory'],
    ['account-edit', 'updateAccount'],
  ] as const)('%s clears an obsolete draft while a pending save settles', async (name, update) => {
    const pendingUpdate = deferred<unknown>()
    const runtime = runtimeFor()
    runtime.catalog[update].mockReturnValueOnce(pendingUpdate.promise)
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: 'A 身份未保存名称' } })

    const pending = page.onSubmit()
    await Promise.resolve()
    runtime.setRevision(2)
    page.onHide()
    await page.onShow()

    expect(page.data.name).not.toBe('A 身份未保存名称')
    pendingUpdate.resolve(undefined)
    await pending
    expect(page.data.name).not.toBe('A 身份未保存名称')
  })

  it.each([
    ['category-edit', 'category', 'updateCategory'],
    ['account-edit', 'account', 'updateAccount'],
  ] as const)('%s retries a failed catalog read after confirmed conflict reload', async (name, read, update) => {
    const runtime = runtimeFor()
    const first = read === 'category' ? { ...category, name: '初始服务端名称', icon: '初始图标' } : { ...account, name: '初始服务端名称', version: 2 }
    const refreshed = read === 'category'
      ? { ...category, entryType: 'INCOME' as const, name: '刷新后服务端名称', icon: '刷新后图标' }
      : { ...account, accountType: 'BANK' as const, name: '刷新后服务端名称', version: 3 }
    runtime.catalog[read].mockReset()
    runtime.catalog[read]
      .mockImplementationOnce(async () => first)
      .mockRejectedValueOnce(new Error('catalog read failed'))
      .mockImplementationOnce(async () => refreshed)
    runtime.catalog[update].mockRejectedValueOnce({
      code: 'RESOURCE_STATE_CHANGED',
      message: '资源状态已变化，请刷新后重试',
    })
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '本地未保存名称' } })
    await page.onSubmit()

    expect(page.data.canReload).toBe(true)
    await page.onReload()
    expect(page.data.canRetryRead).toBe(true)
    await page.retry()

    expect(runtime.catalog[read]).toHaveBeenCalledTimes(3)
    expect(page.data.name).toBe('本地未保存名称')
    expect(read === 'category' ? page.data.entryType : page.data.accountType).toBe(read === 'category' ? 'INCOME' : 'BANK')
    expect(read === 'category' ? page.data.icon : page.data.version).toBe(read === 'category' ? '初始图标' : 3)
    expect(page.data.canRetryRead).toBe(false)
  })

  it.each([
    ['category-edit', 'updateCategory'],
    ['account-edit', 'updateAccount'],
  ] as const)('%s disables editable controls and ignores input while saving', async (name, update) => {
    const pendingUpdate = deferred<unknown>()
    const runtime = runtimeFor()
    runtime.catalog[update].mockReturnValueOnce(pendingUpdate.promise)
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '保存中的名称' } })

    const pending = page.onSubmit()
    await Promise.resolve()
    await Promise.resolve()
    page.onNameInput({ detail: { value: '不应接受的名称' } })
    page.onSortInput({ detail: { value: '99' } })

    expect(page.data.name).toBe('保存中的名称')
    expect(page.data.sortNo).toBe(0)
    const wxml = readFileSync(new URL(`../../miniprogram/pages/${name}/index.wxml`, import.meta.url), 'utf8')
    const controls = wxml.match(/<(?:input|picker)\b[^>]*>/g) || []
    expect(controls.length).toBeGreaterThan(0)
    expect(controls.every(control => /\sdisabled="{{[^"]*loading[^"]*busy[^"]*}}"/.test(control))).toBe(true)

    pendingUpdate.resolve(undefined)
    await pending
  })

  it.each([
    ['category-edit', 'category'],
    ['account-edit', 'account'],
  ] as const)('%s ignores input while a confirmed force reload is pending', async (name, read) => {
    const pendingRead = deferred<any>()
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '7' })
    await page.onShow()
    page.onNameInput({ detail: { value: '保留的冲突草稿' } })
    page.setData({ canReload: true })
    runtime.catalog[read].mockReturnValueOnce(pendingRead.promise)

    const pending = page.onReload()
    await Promise.resolve()
    await Promise.resolve()
    expect(page.data.loading).toBe(true)
    page.onNameInput({ detail: { value: '加载中不应接受' } })
    expect(page.data.name).toBe('保留的冲突草稿')

    pendingRead.resolve(read === 'category' ? { ...category, name: '重新加载名称' } : { ...account, name: '重新加载名称', version: 3 })
    await pending
    expect(page.data.name).toBe('重新加载名称')
  })
})
