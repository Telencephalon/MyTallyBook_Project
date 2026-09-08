import { afterEach, describe, expect, it, vi } from 'vitest'

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
  return {
    session: {
      getRevision: () => revision,
      getUser: () => ({ role: 'OWNER' }),
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
  }
}

async function loadPage(name: PageName, runtime: Record<string, any>, confirm = true) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => {
    definition = value
  })
  vi.stubGlobal('wx', {
    navigateTo: vi.fn(),
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
  it('keeps the category list read-only for a member', async () => {
    const runtime = runtimeFor()
    runtime.session.getUser = () => ({ role: 'MEMBER' })
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
  ] as const)('%s abandons a hidden pending deletion and is usable when shown again', async (name, remove) => {
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
  ] as const)('%s abandons a hidden pending save and clears busy on return', async (name, update) => {
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
    pendingUpdate.resolve(undefined)
    await pending

    expect(page.data.busy).toBe(false)
    expect(page.data.loading).toBe(false)
    expect(page.data.name).toBe('未保存名称')
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
})
