import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RawRequestOptions } from '../miniprogram/services/http'

type PageName = 'entry-list' | 'statistics'
type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }

const period = { month: '2026-09', rangeType: 'MONTH', startDate: '2026-09-01', endDate: '2026-09-30' }
const summary = { ...period, income: '50.00', expense: '10.00', net: '40.00', entryCount: 2 }
const entry = { id: 40, entryType: 'EXPENSE', amount: '10.00', categoryId: 7, categoryName: '餐饮',
  categoryStatus: 'ACTIVE', accountId: 8, accountName: '现金', accountStatus: 'ACTIVE', entryDate: '2026-09-01',
  note: null, createdBy: 1, creatorName: '成员', createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z', version: 1, canEdit: true, canDelete: true }
const entryPage = { items: [entry], page: 1, pageSize: 20, total: 41 }
const daily = { ...period, page: 1, pageSize: 31, totalDays: 62, totalPages: 2, hasNext: true,
  items: [{ date: '2026-09-01', income: '50.00', expense: '10.00', net: '40.00', entryCount: 2 }] }

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

function runtimeFor() {
  let revision = 1
  let user = { userId: 1, ledgerId: 1, role: 'OWNER' }
  return {
    session: { getRevision: () => revision, getUser: () => user },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    entries: {
      list: vi.fn().mockImplementation(async (query) => ({ ...entryPage, page: query.page })),
      creators: vi.fn().mockResolvedValue({ items: [{ userId: 1, displayName: '成员' }] }),
    },
    catalog: {
      categories: vi.fn().mockResolvedValue({ items: [{ id: 7, entryType: 'EXPENSE', name: '餐饮', icon: null,
        color: null, sortNo: 0, systemDefault: false, status: 'ACTIVE' }] }),
      accounts: vi.fn().mockResolvedValue({ items: [{ id: 8, name: '现金', accountType: 'CASH',
        initialBalance: '0.00', currentBalance: '0.00', sortNo: 0, status: 'ACTIVE', version: 0 }] }),
    },
    statistics: {
      summary: vi.fn().mockResolvedValue(summary),
      daily: vi.fn().mockImplementation(async query => ({ ...daily, page: query.page ?? 1 })),
      categories: vi.fn().mockImplementation(async (_query, entryType) => ({ ...period, entryType,
        total: entryType === 'INCOME' ? '50.00' : '10.00',
        items: [{ id: 7, name: '餐饮', amount: entryType === 'INCOME' ? '50.00' : '10.00', percentage: '100.00', entryCount: 1 }] })),
      accounts: vi.fn().mockResolvedValue({ ...period, items: [{ id: 8, name: '现金', income: '50.00', expense: '10.00', net: '40.00', entryCount: 2 }] }),
      members: vi.fn().mockResolvedValue({ ...period, entryType: 'ALL', total: '60.00', items: [{ id: 1, name: '成员', amount: '60.00', percentage: '100.00', entryCount: 2 }] }),
    },
    changeUser(change: Partial<typeof user>) { user = { ...user, ...change } },
    changeSession() { ++revision },
  }
}

async function loadPage(name: PageName, runtime: ReturnType<typeof runtimeFor>) {
  let definition!: PageShape
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateTo: vi.fn() })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import(`../miniprogram/pages/${name}/index.ts`)
  const updates: object[] = []
  const page = { ...definition, data: structuredClone(definition.data),
    setData(this: PageShape, update: object) { updates.push(update); Object.assign(this.data, update) },
  } as PageShape
  return { page, updates }
}

function businessRead(name: PageName, runtime: ReturnType<typeof runtimeFor>) {
  return name === 'entry-list' ? runtime.entries.list : runtime.statistics.summary
}

async function startWrite() {
  const { HttpClient } = await import('../miniprogram/services/http')
  let request!: RawRequestOptions
  const client = new HttpClient({ environment: { baseUrl: 'http://127.0.0.1:7631', timeoutMs: 10000 },
    getToken: () => 'token', onUnauthorized() {}, executor: value => { request = value } })
  const pending = client.request({ method: 'POST', path: '/api/v1/entries', body: {} })
  return { pending, complete() { request.success({ statusCode: 200, header: {}, data: {
    code: 'OK', message: 'ok', requestId: 'write', timestamp: '2026-09-01T00:00:00Z', data: {},
  } }) } }
}

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T00:00:00Z')) })
afterEach(() => { vi.resetModules(); vi.clearAllMocks(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe.each<PageName>(['entry-list', 'statistics'])('%s tab return', name => {
  it('refreshes authorization but reuses a fresh complete result without clearing visible data', async () => {
    const runtime = runtimeFor()
    const { page, updates } = await loadPage(name, runtime)
    await page.onShow()
    const visible = name === 'entry-list' ? page.data.items : page.data.daily
    const context = deferred<void>()
    runtime.flow.refreshContext.mockReturnValueOnce(context.promise)
    page.onHide(); updates.length = 0
    const returning = page.onShow()
    expect(name === 'entry-list' ? page.data.items : page.data.daily).toBe(visible)
    context.resolve(); await returning
    expect(runtime.flow.refreshContext).toHaveBeenLastCalledWith({ reusePending: true })
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(1)
    expect(runtime.entries.creators).toHaveBeenCalledTimes(1)
    if (name === 'entry-list') {
      expect(runtime.catalog.categories).toHaveBeenCalledTimes(1)
      expect(runtime.catalog.accounts).toHaveBeenCalledTimes(1)
    } else {
      expect(runtime.statistics.categories).toHaveBeenCalledTimes(2)
      expect(runtime.statistics.daily).toHaveBeenCalledTimes(1)
      expect(runtime.statistics.accounts).toHaveBeenCalledTimes(1)
      expect(runtime.statistics.members).toHaveBeenCalledTimes(2)
      expect(updates.some(update => 'daily' in update)).toBe(false)
      expect(page.data.categoryGroups.map((group: any) => group.entryType)).toEqual(['INCOME', 'EXPENSE'])
      expect(page.data.memberGroups.map((group: any) => group.entryType)).toEqual(['INCOME', 'EXPENSE'])
    }
  })

  it('keeps displayed data while an expired result is revalidated and replaces it when complete', async () => {
    const runtime = runtimeFor()
    const { page } = await loadPage(name, runtime)
    await page.onShow()
    const visible = name === 'entry-list' ? page.data.items : page.data.daily
    vi.mocked(Date.now).mockReturnValue(Date.parse('2026-09-01T00:00:15Z'))
    const pending = deferred<any>()
    businessRead(name, runtime).mockReturnValueOnce(pending.promise)
    page.onHide(); const returning = page.onShow()
    await vi.waitFor(() => expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2))
    expect(name === 'entry-list' ? page.data.items : page.data.daily).toBe(visible)
    expect(page.data.loadState).toBe('ready')
    pending.resolve(name === 'entry-list' ? { ...entryPage, items: [] } : { ...summary, income: '75.00' })
    await returning
    expect(name === 'entry-list' ? page.data.items : page.data.summary.income).toEqual(name === 'entry-list' ? [] : '75.00')
  })

  it('invalidates cached data on write start and settlement, including writes during a read', async () => {
    const runtime = runtimeFor()
    const { page } = await loadPage(name, runtime)
    await page.onShow()
    const write = await startWrite()
    const read = deferred<any>()
    businessRead(name, runtime).mockReturnValueOnce(read.promise)
    page.onHide(); const returning = page.onShow()
    await vi.waitFor(() => expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2))
    write.complete(); await write.pending
    read.resolve(name === 'entry-list' ? entryPage : summary); await returning
    page.onHide(); await page.onShow()
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(3)
  })

  it('measures freshness from the beginning of the read rather than its late completion', async () => {
    const runtime = runtimeFor()
    const read = deferred<any>()
    businessRead(name, runtime).mockReturnValueOnce(read.promise)
    const { page } = await loadPage(name, runtime)
    const initial = page.onShow()
    await vi.waitFor(() => expect(businessRead(name, runtime)).toHaveBeenCalledTimes(1))
    vi.mocked(Date.now).mockReturnValue(Date.parse('2026-09-01T00:00:20Z'))
    read.resolve(name === 'entry-list' ? entryPage : summary); await initial
    page.onHide(); await page.onShow()
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2)
  })

  it('bypasses reuse for explicit retry and clears data after refresh failure', async () => {
    const runtime = runtimeFor()
    const { page } = await loadPage(name, runtime)
    await page.onShow(); await page.retry()
    expect(runtime.flow.refreshContext).toHaveBeenLastCalledWith()
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2)
    runtime.flow.refreshContext.mockRejectedValueOnce(new Error('offline'))
    page.onHide(); await page.onShow()
    expect(page.data.loadState).toBe('error')
    expect(name === 'entry-list' ? page.data.items : page.data.daily).toEqual([])
    page.onHide(); await page.onShow()
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(3)
  })

  it.each(['role', 'session'] as const)('does not reuse old data after a %s change', async change => {
    const runtime = runtimeFor()
    const { page } = await loadPage(name, runtime)
    await page.onShow()
    const read = deferred<any>()
    businessRead(name, runtime).mockReturnValueOnce(read.promise)
    if (change === 'role') runtime.flow.refreshContext.mockImplementationOnce(async () => { runtime.changeUser({ role: 'MEMBER' }) })
    else runtime.changeSession()
    page.onHide(); const returning = page.onShow()
    await vi.waitFor(() => expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2))
    expect(name === 'entry-list' ? page.data.items : page.data.daily).toEqual([])
    expect(page.data.createdBy).toBe(0)
    read.resolve(name === 'entry-list' ? entryPage : summary); await returning
    if (change === 'role') expect(page.data.creators).toEqual([])
  })

  it('does not publish or cache an old response after hiding and starting a newer load', async () => {
    const runtime = runtimeFor()
    const old = deferred<any>()
    businessRead(name, runtime).mockReturnValueOnce(old.promise)
    const { page } = await loadPage(name, runtime)
    const initial = page.onShow()
    await vi.waitFor(() => expect(businessRead(name, runtime)).toHaveBeenCalledTimes(1))
    page.onHide(); await page.onShow()
    old.resolve(name === 'entry-list' ? { ...entryPage, items: [] } : { ...summary, income: '999.00' })
    await initial
    expect(name === 'entry-list' ? page.data.items : page.data.summary.income).toEqual(name === 'entry-list' ? [entry] : '50.00')
    page.onHide(); await page.onShow()
    expect(businessRead(name, runtime)).toHaveBeenCalledTimes(2)
  })
})

describe('query-specific tab snapshots', () => {
  it('does not reuse entries for unapplied filter changes or treat a partial filtered load as a full refresh', async () => {
    const runtime = runtimeFor()
    const { page } = await loadPage('entry-list', runtime)
    await page.onShow()
    page.onKeyword({ detail: { value: '晚餐' } })
    page.onHide(); await page.onShow()
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '晚餐' }))
    expect(runtime.entries.list).toHaveBeenCalledTimes(2)
    await page.nextPage()
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    page.onHide(); await page.onShow()
    expect(runtime.entries.list).toHaveBeenCalledTimes(4)
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    expect(runtime.catalog.categories).toHaveBeenCalledTimes(3)
  })

  it('uses applied statistics scope and refreshes the complete tab after partial rankings or paging', async () => {
    const runtime = runtimeFor()
    const { page } = await loadPage('statistics', runtime)
    await page.onShow()
    await page.onScopeChange({ detail: { value: '2' } })
    page.onCustomStartChange({ detail: { value: '2025-01-01' } })
    page.onHide(); await page.onShow()
    expect(runtime.statistics.summary).toHaveBeenCalledTimes(1)
    await page.onDirectionChange({ detail: { value: '1' } })
    expect(page.data.categoryGroups).toHaveLength(1)
    page.onHide(); await page.onShow()
    expect(runtime.statistics.summary).toHaveBeenCalledTimes(2)
    expect(runtime.statistics.categories).toHaveBeenLastCalledWith('2026-09', 'INCOME')
    await page.onDailyNext()
    page.onHide(); await page.onShow()
    expect(runtime.statistics.summary).toHaveBeenCalledTimes(3)
    expect(runtime.statistics.daily).toHaveBeenLastCalledWith({ month: '2026-09', page: 2 })
  })
})
