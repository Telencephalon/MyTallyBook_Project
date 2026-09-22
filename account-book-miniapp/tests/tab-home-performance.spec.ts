import { afterEach, describe, expect, it, vi } from 'vitest'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }
const entry = { id: 40, entryType: 'INCOME', amount: '123.00', categoryName: '生活', personName: '测试', note: null }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}

async function setup() {
  let user = { userId: 1, memberId: 1, ledgerId: 1, nickname: '用户', displayName: null, role: 'OWNER' }
  let revision = 1
  const runtime = {
    session: { getUser: () => user, getRevision: () => revision, getToken: () => 'token',
      getLedger: () => ({ id: 1, name: '账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: null }) },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    entries: { list: vi.fn().mockResolvedValue({ items: [entry], page: 1, pageSize: 5, total: 1 }),
      creators: vi.fn().mockResolvedValue({ items: [] }) },
  }
  let definition!: PageShape
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateTo: vi.fn() })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import('../miniprogram/pages/home/index')
  const page = { ...definition, data: structuredClone(definition.data),
    setData(this: PageShape, value: object) { Object.assign(this.data, value) } } as PageShape
  return { page, runtime, demote() { user = { ...user, role: 'MEMBER' } },
    changeIdentity() { user = { ...user, userId: 2 }; ++revision } }
}

afterEach(() => { vi.resetModules(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('home tab performance', () => {
  it('keeps rendered entries on return and reuses fresh data only after live context validation', async () => {
    const { page, runtime } = await setup()
    await page.onShow()
    page.onHide()
    const context = deferred<void>()
    runtime.flow.refreshContext.mockReturnValueOnce(context.promise)
    const returning = page.onShow()
    expect(page.data.recentEntries).toEqual([entry])
    expect(page.data.canManageCatalog).toBe(false)
    context.resolve()
    await returning
    expect(runtime.flow.refreshContext).toHaveBeenCalledTimes(2)
    expect(runtime.entries.list).toHaveBeenCalledTimes(1)
    expect(runtime.entries.creators).toHaveBeenCalledTimes(1)
    expect(page.data.canManageCatalog).toBe(true)
  })

  it('enables bookkeeping after context validation without waiting for the recent list', async () => {
    const { page, runtime } = await setup()
    const recent = deferred<any>()
    const started = deferred<void>()
    runtime.entries.list.mockImplementationOnce(() => { started.resolve(); return recent.promise })
    const initial = page.onShow()
    await started.promise
    expect(page.data.loading).toBe(false)
    expect(page.data.recentLoading).toBe(true)
    page.openEntryCreate()
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/entry-create/index' })
    recent.resolve({ items: [entry], page: 1, pageSize: 5, total: 1 })
    await initial
  })

  it('refreshes expired data and explicit retries instead of extending freshness on each return', async () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const { page, runtime } = await setup()
    await page.onShow()
    now = 10000
    await page.onShow()
    expect(runtime.entries.list).toHaveBeenCalledTimes(1)
    now = 16000
    await page.onShow()
    expect(runtime.entries.list).toHaveBeenCalledTimes(2)
    await page.retryRecent()
    expect(runtime.entries.list).toHaveBeenCalledTimes(3)
  })

  it.each(['demote', 'changeIdentity'] as const)('drops old entries and reloads after %s during validation', async action => {
    const context = await setup()
    await context.page.onShow()
    context.runtime.flow.refreshContext.mockImplementationOnce(async () => { context[action]() })
    context.runtime.entries.list.mockResolvedValue({ items: [], page: 1, pageSize: 5, total: 0 })
    await context.page.onShow()
    expect(context.page.data.recentEntries).toEqual([])
    if (action === 'demote') expect(context.page.data.canManageCatalog).toBe(false)
  })
})
