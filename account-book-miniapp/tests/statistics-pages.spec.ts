import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }

const period = { rangeType: 'MONTH', startDate: '2024-09-01', endDate: '2024-09-30' }
const summary = { month: '2024-09', ...period, income: '100.30', expense: '30.05', net: '70.25', entryCount: 3 }
const daily = { month: '2024-09', ...period, page: 1, pageSize: 31, totalDays: 30, totalPages: 1, hasNext: false, items: [{ date: '2024-09-01', income: '100.30', expense: '30.05', net: '70.25', entryCount: 3 }] }
const categories = { month: '2024-09', ...period, entryType: 'EXPENSE', total: '30.05', items: [{ id: 7, name: '餐饮', amount: '30.05', percentage: '100.00', entryCount: 1 }] }
const accounts = { month: '2024-09', ...period, items: [{ id: 8, name: '现金', income: '100.30', expense: '30.05', net: '70.25', entryCount: 3 }] }
const members = { month: '2024-09', ...period, entryType: 'EXPENSE', total: '30.05', items: [{ id: 1, name: '历史成员', amount: '30.05', percentage: '100.00', entryCount: 1 }] }

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

function runtimeFor() {
  let revision = 1
  return {
    session: { getRevision: () => revision, getUser: () => ({ userId: 1, ledgerId: 1, role: 'OWNER' }) },
    entries: { creators: vi.fn().mockResolvedValue({ items: [] }) },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    statistics: {
      summary: vi.fn().mockResolvedValue(summary), daily: vi.fn().mockResolvedValue(daily),
      categories: vi.fn().mockResolvedValue(categories), accounts: vi.fn().mockResolvedValue(accounts),
      members: vi.fn().mockResolvedValue(members),
    },
    setRevision(value: number) { revision = value },
  }
}

async function loadPage(runtime: ReturnType<typeof runtimeFor>) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateBack: vi.fn() })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import('../miniprogram/pages/statistics/index')
  return {
    ...definition!, data: structuredClone(definition!.data),
    setData(this: PageShape, update: object) { Object.assign(this.data, update) },
  } as PageShape
}

afterEach(() => { vi.resetModules(); vi.clearAllMocks(); vi.unstubAllGlobals() })

describe('statistics page', () => {
  it('ranks the same member separately by income and expense with independent totals', async () => {
    const runtime = runtimeFor()
    runtime.statistics.members.mockImplementation(async (_query, entryType) => {
      if (entryType === 'INCOME') return { ...members, entryType, total: '123.00', items: [
        { id: 1, name: 'Gitta', amount: '92.25', percentage: '75.00', entryCount: 1 },
        { id: 2, name: '成员二', amount: '30.75', percentage: '25.00', entryCount: 1 },
      ] }
      return { ...members, entryType, total: '5121.00', items: [
        { id: 1, name: 'Gitta', amount: '5121.00', percentage: '100.00', entryCount: 1 },
      ] }
    })
    const page = await loadPage(runtime)
    await page.onShow()
    expect(page.data.memberGroups).toEqual([
      { entryType: 'INCOME', label: '收入', total: '123.00', items: [
        { id: 1, name: 'Gitta', amount: '92.25', percentage: '75.00', entryCount: 1, width: 75 },
        { id: 2, name: '成员二', amount: '30.75', percentage: '25.00', entryCount: 1, width: 25 },
      ] },
      { entryType: 'EXPENSE', label: '支出', total: '5121.00', items: [
        { id: 1, name: 'Gitta', amount: '5121.00', percentage: '100.00', entryCount: 1, width: 100 },
      ] },
    ])
    expect(runtime.statistics.members).not.toHaveBeenCalledWith(expect.anything(), 'ALL')
  })

  it('switches member ranking direction and preserves an empty income group without mixing expenses', async () => {
    const runtime = runtimeFor()
    runtime.statistics.members.mockImplementation(async (_query, entryType) => ({
      ...members, entryType, ...(entryType === 'INCOME' ? { total: '0.00', items: [] } : {}),
    }))
    const page = await loadPage(runtime)
    await page.onShow()
    await page.onDirectionChange({ detail: { value: '0' } })
    expect(page.data.memberGroups).toEqual([expect.objectContaining({ entryType: 'EXPENSE', total: '30.05' })])
    await page.onDirectionChange({ detail: { value: '1' } })
    expect(page.data.memberGroups).toEqual([{ entryType: 'INCOME', label: '收入', total: '0.00', items: [] }])
    await page.onDirectionChange({ detail: { value: '2' } })
    expect(page.data.memberGroups).toHaveLength(2)
    expect(page.data.memberGroups[0]).toMatchObject({ entryType: 'INCOME', total: '0.00', items: [] })
    expect(page.data.loadState).toBe('ready')
  })

  it('clears member groups if either directional ranking fails', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    runtime.statistics.members.mockImplementation(async (_query, entryType) => {
      if (entryType === 'EXPENSE') throw new Error('expense ranking offline')
      return { ...members, entryType }
    })
    await page.onDirectionChange({ detail: { value: '2' } })
    expect(page.data.memberGroups).toEqual([])
    expect(page.data.loadState).toBe('error')
    expect(page.data.loading).toBe(false)
  })

  it('keeps income and expense category amounts and percentages separate for the same category', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockResolvedValue({ ...summary, income: '123.00', expense: '3456.00', net: '-3333.00' })
    runtime.statistics.categories.mockImplementation(async (_query, entryType) => {
      if (entryType === 'INCOME') return { ...categories, entryType, total: '123.00', items: [
        { id: 7, name: '生活', amount: '30.75', percentage: '25.00', entryCount: 1 },
        { id: 8, name: '人情', amount: '92.25', percentage: '75.00', entryCount: 1 },
      ] }
      if (entryType === 'EXPENSE') return { ...categories, entryType, total: '3456.00', items: [
        { id: 7, name: '生活', amount: '3456.00', percentage: '100.00', entryCount: 1 },
      ] }
      return { ...categories, entryType, total: '3579.00' }
    })
    const page = await loadPage(runtime)
    await page.onShow()

    expect(page.data.categoryGroups).toEqual([
      { entryType: 'INCOME', label: '收入', total: '123.00', items: [
        { id: 7, name: '生活', amount: '30.75', percentage: '25.00', entryCount: 1, width: 25 },
        { id: 8, name: '人情', amount: '92.25', percentage: '75.00', entryCount: 1, width: 75 },
      ] },
      { entryType: 'EXPENSE', label: '支出', total: '3456.00', items: [
        { id: 7, name: '生活', amount: '3456.00', percentage: '100.00', entryCount: 1, width: 100 },
      ] },
    ])
    expect(runtime.statistics.categories).not.toHaveBeenCalledWith(expect.anything(), 'ALL')
  })

  it('filters category groups by direction and keeps an empty direction distinct from an error', async () => {
    const runtime = runtimeFor()
    runtime.statistics.categories.mockImplementation(async (_query, entryType) => ({
      ...categories, entryType, ...(entryType === 'INCOME' ? { total: '0.00', items: [] } : {}),
    }))
    const page = await loadPage(runtime)
    await page.onShow()
    await page.onDirectionChange({ detail: { value: '0' } })
    expect(page.data.categoryGroups).toHaveLength(1)
    expect(page.data.categoryGroups[0]).toMatchObject({ entryType: 'EXPENSE', total: '30.05' })
    await page.onDirectionChange({ detail: { value: '1' } })
    expect(page.data.categoryGroups).toEqual([{ entryType: 'INCOME', label: '收入', total: '0.00', items: [] }])
    await page.onDirectionChange({ detail: { value: '2' } })
    expect(page.data.categoryGroups).toHaveLength(2)
    expect(page.data.categoryGroups[0]).toMatchObject({ entryType: 'INCOME', total: '0.00', items: [] })
    expect(page.data.loadState).toBe('ready')
    expect(page.data.errorMessage).toBe('')
  })

  it('ignores a late category half from an obsolete all-direction request', async () => {
    const runtime = runtimeFor()
    const pendingExpense = deferred<typeof categories>()
    runtime.statistics.categories.mockImplementation(async (_query, entryType) => entryType === 'EXPENSE'
      ? pendingExpense.promise : { ...categories, entryType, total: '123.00' })
    const page = await loadPage(runtime)
    const initialLoad = page.onShow()
    await Promise.resolve(); await Promise.resolve()
    await page.onDirectionChange({ detail: { value: '1' } })
    const latest = structuredClone(page.data.categoryGroups)
    expect(latest).toEqual([expect.objectContaining({ entryType: 'INCOME', total: '123.00' })])
    pendingExpense.resolve({ ...categories, total: '999.00' })
    await initialLoad
    expect(page.data.categoryGroups).toEqual(latest)
    expect(page.data.loading).toBe(false)
  })

  it('clears category groups when either direction fails instead of presenting a partial breakdown', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    runtime.statistics.categories.mockImplementation(async (_query, entryType) => {
      if (entryType === 'EXPENSE') throw new Error('expense categories offline')
      return { ...categories, entryType }
    })
    await page.onDirectionChange({ detail: { value: '2' } })
    expect(page.data.categoryGroups).toEqual([])
    expect(page.data.loadState).toBe('error')
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.loading).toBe(false)
  })

  it('uses the approved navigation title', () => {
    const config = JSON.parse(readFileSync(resolve(__dirname, '../miniprogram/pages/statistics/index.json'), 'utf8'))
    expect(config.navigationBarTitleText).toBe('统计')
  })

  it('refreshes the unchosen initial month at first page entry but preserves explicit month on return', async () => {
    const today = vi.fn().mockReturnValue('2024-01-31')
    vi.doMock('../miniprogram/utils/bookkeeping', () => ({ shanghaiToday: today }))
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    runtime.statistics.summary.mockImplementation(query => ({
      ...summary,
      month: typeof query === 'string' ? query : summary.month,
    }))
    today.mockReturnValue('2024-02-01')

    await page.onShow()

    expect(runtime.statistics.summary).toHaveBeenCalledWith('2024-02')
    expect(page.data.month).toBe('2024-02')

    runtime.statistics.summary.mockClear()
    await page.onMonthChange({ detail: { value: '2024-01' } })
    today.mockReturnValue('2024-03-01')
    runtime.statistics.summary.mockClear()

    await page.onShow()

    expect(runtime.statistics.summary).not.toHaveBeenCalled()
    expect(page.data.month).toBe('2024-01')
  })

  it('loads five independent views for the selected month and switches direction only for rankings', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })

    await page.onShow()
    expect(runtime.statistics.summary).toHaveBeenCalledWith('2024-09')
    expect(runtime.statistics.daily).toHaveBeenCalledWith('2024-09')
    expect(runtime.statistics.categories).toHaveBeenCalledWith('2024-09', 'INCOME')
    expect(runtime.statistics.categories).toHaveBeenCalledWith('2024-09', 'EXPENSE')
    expect(runtime.statistics.accounts).toHaveBeenCalledWith('2024-09')
    expect(runtime.statistics.members).toHaveBeenCalledWith('2024-09', 'INCOME')
    expect(runtime.statistics.members).toHaveBeenCalledWith('2024-09', 'EXPENSE')

    Object.values(runtime.statistics).forEach(mock => mock.mockClear())
    await page.onDirectionChange({ detail: { value: '1' } })
    expect(page.data.entryType).toBe('INCOME')
    expect(runtime.statistics.categories).toHaveBeenCalledWith('2024-09', 'INCOME')
    expect(runtime.statistics.members).toHaveBeenCalledWith('2024-09', 'INCOME')
    expect(runtime.statistics.summary).not.toHaveBeenCalled()
    expect(runtime.statistics.daily).not.toHaveBeenCalled()
    expect(runtime.statistics.accounts).not.toHaveBeenCalled()
  })

  it('offers an all direction and requests both ranking dimensions together', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    await page.onShow()
    await page.onDirectionChange({ detail: { value: '2' } })
    expect(page.data.directionLabels).toEqual(['支出', '收入', '全部'])
    expect(page.data.entryType).toBe('ALL')
    expect(runtime.statistics.categories).toHaveBeenCalledWith(expect.anything(), 'INCOME')
    expect(runtime.statistics.categories).toHaveBeenLastCalledWith(expect.anything(), 'EXPENSE')
    expect(runtime.statistics.members).toHaveBeenCalledWith(expect.anything(), 'INCOME')
    expect(runtime.statistics.members).toHaveBeenLastCalledWith(expect.anything(), 'EXPENSE')
  })

  it('month switching reloads all five views and stale out-of-order data cannot overwrite it', async () => {
    const runtime = runtimeFor()
    const old = deferred<typeof summary>()
    runtime.statistics.summary.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ ...summary, month: '2024-08', net: '9.00' })
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })

    const first = page.onShow()
    await Promise.resolve()
    const second = page.onMonthChange({ detail: { value: '2024-08' } })
    await second
    old.resolve(summary)
    await first

    expect(page.data.month).toBe('2024-08')
    expect(page.data.summary.net).toBe('9.00')
    expect(runtime.statistics.daily).toHaveBeenCalledWith('2024-08')
    expect(runtime.statistics.accounts).toHaveBeenCalledWith('2024-08')
  })

  it('direction change during initial full load replaces it with a complete latest-direction snapshot', async () => {
    const runtime = runtimeFor()
    const oldSummary = deferred<typeof summary>()
    runtime.statistics.summary.mockReturnValueOnce(oldSummary.promise)
      .mockResolvedValueOnce({ ...summary, net: '88.00' })
    runtime.statistics.categories.mockImplementation(async (_month, entryType) => ({
      ...categories, entryType, total: entryType === 'INCOME' ? '100.30' : '30.05',
    }))
    runtime.statistics.members.mockImplementation(async (_month, entryType) => ({
      ...members, entryType, total: entryType === 'INCOME' ? '100.30' : '30.05',
    }))
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })

    const initialLoad = page.onShow()
    await Promise.resolve()
    await Promise.resolve()
    const directionLoad = page.onDirectionChange({ detail: { value: '1' } })
    await directionLoad
    oldSummary.resolve(summary)
    await initialLoad

    expect(page.data.entryType).toBe('INCOME')
    expect(page.data.summary.net).toBe('88.00')
    expect(page.data.daily).not.toEqual([])
    expect(page.data.accountItems).not.toEqual([])
    expect(page.data.categoryGroups).toEqual([expect.objectContaining({ entryType: 'INCOME', total: '100.30' })])
    expect(page.data.memberGroups).toEqual([expect.objectContaining({ entryType: 'INCOME', total: '100.30' })])
    expect(runtime.statistics.summary).toHaveBeenCalledTimes(2)
    expect(runtime.statistics.categories).toHaveBeenLastCalledWith('2024-09', 'INCOME')
  })

  it('direction change during month reload keeps the new month complete and ignores the old error', async () => {
    const runtime = runtimeFor()
    const oldMonthSummary = deferred<typeof summary>()
    runtime.statistics.summary.mockResolvedValueOnce(summary)
      .mockReturnValueOnce(oldMonthSummary.promise)
      .mockResolvedValueOnce({ ...summary, month: '2024-08', net: '8.00' })
    runtime.statistics.daily.mockImplementation(async month => ({ ...daily, month }))
    runtime.statistics.accounts.mockImplementation(async month => ({ ...accounts, month }))
    runtime.statistics.categories.mockImplementation(async (month, entryType) => ({
      ...categories, month, entryType, total: entryType === 'INCOME' ? '80.00' : '20.00',
    }))
    runtime.statistics.members.mockImplementation(async (month, entryType) => ({
      ...members, month, entryType, total: entryType === 'INCOME' ? '80.00' : '20.00',
    }))
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()

    const monthLoad = page.onMonthChange({ detail: { value: '2024-08' } })
    await Promise.resolve()
    await Promise.resolve()
    const directionLoad = page.onDirectionChange({ detail: { value: '1' } })
    await directionLoad
    oldMonthSummary.reject(new Error('obsolete month request failed'))
    await monthLoad

    expect(page.data.month).toBe('2024-08')
    expect(page.data.entryType).toBe('INCOME')
    expect(page.data.summary.net).toBe('8.00')
    expect(page.data.daily).not.toEqual([])
    expect(page.data.accountItems).not.toEqual([])
    expect(page.data.categoryGroups).toEqual([expect.objectContaining({ entryType: 'INCOME', total: '80.00' })])
    expect(page.data.memberGroups).toEqual([expect.objectContaining({ entryType: 'INCOME', total: '80.00' })])
    expect(page.data.errorMessage).toBe('')
    expect(runtime.statistics.categories).toHaveBeenLastCalledWith('2024-08', 'INCOME')
  })

  it('direction change after a settled initial failure performs a full recovery load', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockRejectedValueOnce(new Error('initial load failed'))
      .mockResolvedValueOnce({ ...summary, net: '91.00' })
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    expect(page.data.loadState).toBe('error')

    await page.onDirectionChange({ detail: { value: '1' } })

    expect(runtime.statistics.summary).toHaveBeenCalledTimes(2)
    expect(page.data.entryType).toBe('INCOME')
    expect(page.data.summary.net).toBe('91.00')
    expect(page.data.daily).not.toEqual([])
    expect(page.data.accountItems).not.toEqual([])
    expect(page.data.errorMessage).toBe('')
    expect(page.data.loadState).toBe('ready')
  })

  it('direction change after a settled month failure recovers the same month as a full snapshot', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockResolvedValueOnce(summary)
      .mockRejectedValueOnce(new Error('month load failed'))
      .mockResolvedValueOnce({ ...summary, month: '2024-08', net: '18.00' })
    runtime.statistics.daily.mockImplementation(async month => ({ ...daily, month }))
    runtime.statistics.accounts.mockImplementation(async month => ({ ...accounts, month }))
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    await page.onMonthChange({ detail: { value: '2024-08' } })
    expect(page.data.loadState).toBe('error')

    await page.onDirectionChange({ detail: { value: '1' } })

    expect(page.data.month).toBe('2024-08')
    expect(page.data.entryType).toBe('INCOME')
    expect(page.data.summary.net).toBe('18.00')
    expect(page.data.daily).not.toEqual([])
    expect(page.data.accountItems).not.toEqual([])
    expect(page.data.errorMessage).toBe('')
  })

  it('a repeated full-load failure after direction change keeps error and retry visible', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockRejectedValueOnce(new Error('initial load failed'))
      .mockRejectedValueOnce(new Error('recovery load failed'))
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()

    await page.onDirectionChange({ detail: { value: '1' } })

    expect(runtime.statistics.summary).toHaveBeenCalledTimes(2)
    expect(page.data.summary).toBeNull()
    expect(page.data.loadState).toBe('error')
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.loading).toBe(false)
  })

  it.each(['onHide', 'onUnload'])('a response after %s cannot paint the departed page', async lifecycle => {
    const runtime = runtimeFor()
    const pending = deferred<typeof summary>()
    runtime.statistics.summary.mockReturnValueOnce(pending.promise)
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })

    const operation = page.onShow()
    await Promise.resolve()
    page[lifecycle]()
    const data = structuredClone(page.data)
    pending.resolve(summary)
    await operation

    expect(page.data).toEqual(data)
  })

  it('current failure clears old numeric data and distinguishes read error from empty data', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    runtime.statistics.summary.mockRejectedValueOnce(new Error('offline'))

    await page.retry()

    expect(page.data.summary).toBeNull()
    expect(page.data.daily).toEqual([])
    expect(page.data.categoryGroups).toEqual([])
    expect(page.data.accountItems).toEqual([])
    expect(page.data.memberGroups).toEqual([])
    expect(page.data.loadState).toBe('error')
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.loading).toBe(false)
  })

  it('zero-entry current response is an empty state rather than a read error', async () => {
    const runtime = runtimeFor()
    runtime.statistics.summary.mockResolvedValueOnce({ ...summary, income: '0.00', expense: '0.00', net: '0.00', entryCount: 0 })
    runtime.statistics.categories.mockResolvedValue({ ...categories, total: '0.00', items: [] })
    runtime.statistics.accounts.mockResolvedValueOnce({ ...accounts, items: [] })
    runtime.statistics.members.mockResolvedValue({ ...members, total: '0.00', items: [] })
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })

    await page.onShow()

    expect(page.data.loadState).toBe('empty')
    expect(page.data.errorMessage).toBe('')
  })

  it('applies all-time scope to all five views and resets daily pagination', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    await page.onScopeChange({ detail: { value: '1' } })

    const query = { rangeType: 'ALL' }
    expect(runtime.statistics.summary).toHaveBeenCalledWith(query)
    expect(runtime.statistics.daily).toHaveBeenCalledWith({ ...query, page: 1 })
    expect(runtime.statistics.categories).toHaveBeenCalledWith(query, 'INCOME')
    expect(runtime.statistics.categories).toHaveBeenCalledWith(query, 'EXPENSE')
    expect(runtime.statistics.accounts).toHaveBeenCalledWith(query)
    expect(runtime.statistics.members).toHaveBeenCalledWith(query, 'INCOME')
    expect(runtime.statistics.members).toHaveBeenCalledWith(query, 'EXPENSE')
    expect(page.data.dailyPage).toBe(1)
  })

  it('keeps custom draft dates separate until validation applies the range', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    await page.onScopeChange({ detail: { value: '2' } })
    page.onCustomStartChange({ detail: { value: '2024-03-05' } })
    page.onCustomEndChange({ detail: { value: '2024-03-01' } })
    await page.onApplyCustomRange()

    expect(page.data.errorMessage).not.toBe('')
    expect(runtime.statistics.summary).not.toHaveBeenCalled()
    expect(page.data.appliedRangeLabel).toBe('2024-09')

    page.onCustomStartChange({ detail: { value: '2024-02-29' } })
    page.onCustomEndChange({ detail: { value: '2024-03-01' } })
    await page.onApplyCustomRange()

    const query = { startDate: '2024-02-29', endDate: '2024-03-01' }
    expect(runtime.statistics.summary).toHaveBeenCalledWith(query)
    expect(runtime.statistics.daily).toHaveBeenCalledWith({ ...query, page: 1 })
    expect(page.data.appliedRangeLabel).toBe('2024-02-29 至 2024-03-01')
    expect(runtime.statistics.members).toHaveBeenCalledWith(query, 'INCOME')
    expect(runtime.statistics.members).toHaveBeenCalledWith(query, 'EXPENSE')
    expect(runtime.statistics.categories).toHaveBeenCalledWith(query, 'INCOME')
    expect(runtime.statistics.categories).toHaveBeenCalledWith(query, 'EXPENSE')
  })

  it('daily next page only fetches daily rows and keeps full-range summary and rankings', async () => {
    const runtime = runtimeFor()
    runtime.statistics.daily.mockResolvedValueOnce({ ...daily, hasNext: true, totalPages: 2 })
      .mockResolvedValueOnce({ ...daily, page: 2, hasNext: false, items: [{ ...daily.items[0], date: '2024-10-01' }] })
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    await page.onDailyNext()

    expect(runtime.statistics.daily).toHaveBeenCalledWith({ month: '2024-09', page: 2 })
    expect(runtime.statistics.summary).not.toHaveBeenCalled()
    expect(runtime.statistics.categories).not.toHaveBeenCalled()
    expect(runtime.statistics.accounts).not.toHaveBeenCalled()
    expect(runtime.statistics.members).not.toHaveBeenCalled()
    expect(page.data.summary).toEqual(summary)
    expect(page.data.daily[0].date).toBe('2024-10-01')
  })

  it('rejects an out-of-order daily page when a newer scope load is active', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    page.setData({ dailyHasNext: true })
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    const stalePage = deferred<typeof daily>()
    runtime.statistics.daily.mockReturnValueOnce(stalePage.promise)
    const oldPageLoad = page.onDailyNext()
    await Promise.resolve()
    await Promise.resolve()

    const latestSummary = deferred<typeof summary>()
    runtime.statistics.summary.mockReturnValueOnce(latestSummary.promise)
    const latestScopeLoad = page.onScopeChange({ detail: { value: '1' } })
    await Promise.resolve()
    await Promise.resolve()
    expect(page.data.loading).toBe(true)

    stalePage.resolve({ ...daily, page: 2, items: [{ ...daily.items[0], date: '2099-01-01' }] })
    await oldPageLoad

    expect(page.data.loading).toBe(true)
    expect(page.data.daily).toEqual([])

    latestSummary.resolve({ ...summary, rangeType: 'ALL', startDate: '2024-01-01', endDate: '2024-09-30' })
    await latestScopeLoad

    expect(page.data.appliedScope).toBe('ALL')
    expect(page.data.loading).toBe(false)
  })

  it('rejects an out-of-order daily page while a newer direction load is active', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    page.setData({ dailyHasNext: true })
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    const stalePage = deferred<typeof daily>()
    runtime.statistics.daily.mockReturnValueOnce(stalePage.promise)
    const oldPageLoad = page.onDailyNext()
    await Promise.resolve()
    await Promise.resolve()

    const latestCategories = deferred<typeof categories>()
    const latestMembers = deferred<typeof members>()
    runtime.statistics.categories.mockReturnValueOnce(latestCategories.promise)
    runtime.statistics.members.mockReturnValueOnce(latestMembers.promise)
    const latestDirectionLoad = page.onDirectionChange({ detail: { value: '1' } })
    await Promise.resolve()
    await Promise.resolve()
    expect(page.data.loading).toBe(true)

    stalePage.resolve({ ...daily, page: 2, items: [{ ...daily.items[0], date: '2099-02-01' }] })
    await oldPageLoad

    expect(page.data.loading).toBe(true)
    expect(page.data.daily).toEqual(daily.items)

    latestCategories.resolve({ ...categories, entryType: 'INCOME' })
    latestMembers.resolve({ ...members, entryType: 'INCOME' })
    await latestDirectionLoad

    expect(page.data.entryType).toBe('INCOME')
    expect(page.data.loading).toBe(false)
  })

  it('clears previous identity statistics when the session revision changes during a daily request', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(runtime)
    page.setData({ month: '2024-09' })
    await page.onShow()
    page.setData({ dailyHasNext: true })
    Object.values(runtime.statistics).forEach(mock => mock.mockClear())

    const stalePage = deferred<typeof daily>()
    runtime.statistics.daily.mockReturnValueOnce(stalePage.promise)
    const oldPageLoad = page.onDailyNext()
    await Promise.resolve()
    await Promise.resolve()

    runtime.setRevision(2)
    stalePage.resolve({ ...daily, page: 2, items: [{ ...daily.items[0], date: '2099-03-01' }] })
    await oldPageLoad

    expect(page.data.loading).toBe(false)
    expect(page.data.daily).toEqual([])
    expect(page.data.summary).toBeNull()
  })
})
