import { afterEach, describe, expect, it, vi } from 'vitest'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }
const user = { userId: 1, memberId: 11, nickname: '昵称', displayName: null, avatarUrl: null, ledgerId: 1, role: 'OWNER' as const }
const ledger = { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 }
const summary = { month: '2024-09', income: '100.30', expense: '30.05', net: '70.25', entryCount: 3 }
const entry = { id: 40, entryType: 'EXPENSE', amount: '30.05', categoryName: '餐饮', accountName: '现金', entryDate: '2024-09-01', note: null }

function runtimeFor() {
  let revision = 1
  return {
    session: { getRevision: () => revision, getToken: () => 'token', getUser: () => user, getLedger: () => ledger },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined), logout: vi.fn().mockResolvedValue('UNCHANGED') },
    statistics: { summary: vi.fn().mockResolvedValue(summary) },
    entries: { list: vi.fn().mockResolvedValue({ items: [entry], page: 1, pageSize: 5, total: 1 }) },
    setRevision(value: number) { revision = value },
  }
}

async function loadPage(runtime: ReturnType<typeof runtimeFor>) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateTo: vi.fn(), reLaunch: vi.fn() })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import('../miniprogram/pages/home/index')
  return {
    ...definition!, data: structuredClone(definition!.data),
    setData(this: PageShape, update: object) { Object.assign(this.data, update) },
  } as PageShape
}

afterEach(() => { vi.resetModules(); vi.clearAllMocks(); vi.unstubAllGlobals() })

describe('home bookkeeping dashboard', () => {
  it('loads the current-month summary and recent five independently of any month filter', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)

    await page.onShow()

    expect(runtime.statistics.summary).toHaveBeenCalledWith()
    expect(runtime.entries.list).toHaveBeenCalledWith({ page: 1, pageSize: 5 })
    expect(page.data.summary).toEqual(summary)
    expect(page.data.recentEntries).toEqual([entry])
  })

  it('summary failure is visible and never represented as a successful zero summary', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage(runtime)

    await page.onShow()

    expect(page.data.summaryError).not.toBe('')
    expect(page.data.summary).toBeNull()
    expect(page.data.recentEntries).toEqual([entry])
  })

  it('recent failure does not erase a successful summary', async () => {
    const runtime = runtimeFor()
    runtime.entries.list.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage(runtime)

    await page.onShow()

    expect(page.data.summary).toEqual(summary)
    expect(page.data.recentError).not.toBe('')
    expect(page.data.recentEntries).toEqual([])
  })

  it('summary retry does not request or clear the successful recent section', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    runtime.statistics.summary.mockRejectedValueOnce(new Error('summary retry failed'))
    runtime.entries.list.mockClear()

    await page.retrySummary()

    expect(runtime.entries.list).not.toHaveBeenCalled()
    expect(page.data.recentEntries).toEqual([entry])
    expect(page.data.recentError).toBe('')
    expect(page.data.summary).toBeNull()
    expect(page.data.summaryError).not.toBe('')
  })

  it('recent retry does not request or clear the successful summary section', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    runtime.entries.list.mockRejectedValueOnce(new Error('recent retry failed'))
    runtime.statistics.summary.mockClear()

    await page.retryRecent()

    expect(runtime.statistics.summary).not.toHaveBeenCalled()
    expect(page.data.summary).toEqual(summary)
    expect(page.data.summaryError).toBe('')
    expect(page.data.recentEntries).toEqual([])
    expect(page.data.recentError).not.toBe('')
  })

  it('a superseded summary retry cannot overwrite or release the latest summary request', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    let resolveOld!: (value: typeof summary) => void
    const old = new Promise<typeof summary>(resolve => { resolveOld = resolve })
    runtime.statistics.summary.mockReturnValueOnce(old)
      .mockResolvedValueOnce({ ...summary, net: '99.00' })

    const oldRetry = page.retrySummary()
    await Promise.resolve()
    const latestRetry = page.retrySummary()
    await latestRetry
    resolveOld({ ...summary, net: '1.00' })
    await oldRetry

    expect(page.data.summary.net).toBe('99.00')
    expect(page.data.summaryError).toBe('')
    expect(page.data.summaryLoading).toBe(false)
  })

  it('a superseded recent retry cannot overwrite the latest recent request', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    let resolveOld!: (value: { items: Array<typeof entry>; page: number; pageSize: number; total: number }) => void
    const old = new Promise<{ items: Array<typeof entry>; page: number; pageSize: number; total: number }>(resolve => { resolveOld = resolve })
    runtime.entries.list.mockReturnValueOnce(old)
      .mockResolvedValueOnce({ items: [{ ...entry, id: 42, amount: '42.00' }], page: 1, pageSize: 5, total: 1 })

    const oldRetry = page.retryRecent()
    await Promise.resolve()
    const latestRetry = page.retryRecent()
    await latestRetry
    resolveOld({ items: [{ ...entry, id: 41, amount: '1.00' }], page: 1, pageSize: 5, total: 1 })
    await oldRetry

    expect(page.data.recentEntries[0].id).toBe(42)
    expect(page.data.recentError).toBe('')
    expect(page.data.recentLoading).toBe(false)
  })

  it('preserves bookkeeping, statistics, catalog, profile, members and invite navigation', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()

    page.openEntryCreate(); page.openEntries(); page.openStatistics(); page.openCategories(); page.openAccounts()
    page.openProfile(); page.openMembers(); page.openInvites()

    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/entry-create/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/entry-list/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/statistics/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/category-list/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/account-list/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/profile/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/member-list/index' })
    expect(wx.navigateTo).toHaveBeenCalledWith({ url: '/pages/invite-create/index' })
  })
})
