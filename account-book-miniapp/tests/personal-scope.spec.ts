import { afterEach, describe, expect, it, vi } from 'vitest'
import { StatisticsApi } from '../miniprogram/services/statistics'
import { LedgerApi } from '../miniprogram/services/ledger'
import { MemberApi } from '../miniprogram/services/member'
import { CatalogFlow } from '../miniprogram/flows/catalog-flow'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }
const owner = { userId: 1, memberId: 11, ledgerId: 1, role: 'OWNER', nickname: '所有者', displayName: null }
const ledger = { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: null }
const summary = { month: '2026-09', income: '2.00', expense: '1.00', net: '1.00', entryCount: 1 }
const daily = { items: [], page: 1, pageSize: 31, totalDays: 60, totalPages: 2, hasNext: true }

function runtimeFor(role = 'OWNER') {
  let user = { ...owner, role }
  let revision = 1
  const items = { items: [] }
  return {
    session: { getUser: () => user, getLedger: () => ledger, getRevision: () => revision, getToken: () => 'token' },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    entries: { creators: vi.fn().mockResolvedValue({ items: [{ userId: 2, displayName: '历史成员' }] }), list: vi.fn().mockResolvedValue({ items: [{ id: 5 }], page: 1, pageSize: 20, total: 1 }),
      detail: vi.fn().mockResolvedValue({ id: 5, createdBy: 2, amount: '999.00', entryType: 'EXPENSE', categoryId: 1, accountId: 1, categoryName: '餐饮', accountName: '现金', categoryStatus: 'ACTIVE', accountStatus: 'ACTIVE', canEdit: true, canDelete: true, entryDate: '2026-09-01', version: 0 }),
      update: vi.fn(), remove: vi.fn() },
    statistics: { summary: vi.fn().mockResolvedValue(summary), daily: vi.fn().mockResolvedValue(daily), categories: vi.fn().mockResolvedValue({ ...items, total: '0.00' }), accounts: vi.fn().mockResolvedValue(items), members: vi.fn().mockResolvedValue({ ...items, total: '0.00' }) },
    catalog: { categories: vi.fn().mockResolvedValue(items), accounts: vi.fn().mockResolvedValue(items), createCategory: vi.fn(), updateCategory: vi.fn(), deleteCategory: vi.fn(), createAccount: vi.fn(), updateAccount: vi.fn(), deleteAccount: vi.fn() },
    setRole(value: string) { user = { ...user, role: value } },
    changeIdentity() { user = { ...user, userId: 9 }; ++revision },
  }
}

async function loadPage(name: string, runtime: ReturnType<typeof runtimeFor>) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateTo: vi.fn(), redirectTo: vi.fn(), showModal: vi.fn() })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import(`../miniprogram/pages/${name}/index.ts`)
  return { ...definition!, data: structuredClone(definition!.data), setData(this: PageShape, update: object) { Object.assign(this.data, update) } } as PageShape
}

afterEach(() => { vi.resetModules(); vi.clearAllMocks(); vi.unstubAllGlobals() })

describe('personal bookkeeping scope', () => {
  it('forwards createdBy through every statistics endpoint including daily paging and directions', async () => {
    const request = vi.fn().mockResolvedValue({})
    const api = new StatisticsApi({ request } as never)
    const query = { rangeType: 'ALL' as const, createdBy: 2 }
    await api.summary(query); await api.daily({ ...query, page: 2 }); await api.accounts(query)
    await api.categories(query, 'ALL'); await api.members(query, 'INCOME')
    expect(request.mock.calls.map(([arg]) => arg.path)).toEqual([
      '/api/v1/statistics/monthly-summary?range=all&createdBy=2',
      '/api/v1/statistics/daily-trend?range=all&page=2&createdBy=2',
      '/api/v1/statistics/accounts?range=all&createdBy=2',
      '/api/v1/statistics/categories?range=all&createdBy=2&entryType=ALL',
      '/api/v1/statistics/members?range=all&createdBy=2&entryType=INCOME',
    ])
    for (const createdBy of [0, -1, 1.5, NaN, Number.MAX_SAFE_INTEGER + 1]) {
      await expect(api.summary({ createdBy } as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    }
  })

  it.each(['home', 'entry-list', 'statistics'])('%s shows names and all-creators choice, resets page and refreshes selected scope', async name => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    await page.onShow()
    expect(page.data.canSelectCreator).toBe(true)
    expect(page.data.creators).toEqual([{ userId: 0, displayName: '全部成员' }, { userId: 2, displayName: '历史成员' }])
    page.setData({ page: 3, dailyPage: 2 })
    await page.onCreator({ detail: { value: '1' } })
    expect(page.data.creatorName).toBe('历史成员')
    if (name === 'statistics') {
      for (const [key, api] of Object.entries(runtime.statistics)) {
        if (key === 'categories' || key === 'members') {
          expect(api).toHaveBeenCalledWith(expect.objectContaining({ createdBy: 2 }), 'INCOME')
          expect(api).toHaveBeenLastCalledWith(expect.objectContaining({ createdBy: 2 }), 'EXPENSE')
        } else {
          expect(api).toHaveBeenLastCalledWith(expect.objectContaining({ createdBy: 2 }))
        }
      }
      expect(page.data.dailyPage).toBe(1)
      await page.onDailyNext()
      expect(runtime.statistics.daily).toHaveBeenLastCalledWith(expect.objectContaining({ createdBy: 2, page: 2 }))
      await page.onDirectionChange({ detail: { value: '1' } })
      expect(runtime.statistics.members).toHaveBeenLastCalledWith(expect.objectContaining({ createdBy: 2 }), 'INCOME')
    } else {
      expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ createdBy: 2, page: 1 }))
    }
    await page.onCreator({ detail: { value: '0' } })
    expect(page.data.createdBy).toBe(0)
  })

  it.each(['home', 'entry-list', 'statistics'])('%s clears creator selection after demotion during context refresh', async name => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    await page.onShow()
    await page.onCreator({ detail: { value: '1' } })
    runtime.flow.refreshContext.mockImplementationOnce(async () => { runtime.setRole('ADMIN') })
    await page.onShow()
    expect(page.data.canSelectCreator).toBe(false)
    expect(page.data.createdBy).toBe(0)
    expect(page.data.creators).toEqual([])
    if (name === 'statistics') expect(runtime.statistics.summary.mock.lastCall?.[0]).not.toHaveProperty('createdBy')
    else expect(runtime.entries.list.mock.lastCall?.[0]?.createdBy).toBeUndefined()
  })

  it.each(['home', 'entry-list', 'statistics'])('%s rejects old owner response after live role changes without revision bump', async name => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    await page.onShow()
    let resolve!: (value: any) => void
    const pending = new Promise(r => { resolve = r })
    if (name === 'statistics') runtime.statistics.summary.mockReturnValueOnce(pending)
    else runtime.entries.list.mockReturnValueOnce(pending)
    const operation = page.onCreator({ detail: { value: '1' } })
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    runtime.setRole('MEMBER')
    resolve(name === 'statistics' ? { ...summary, net: '999.00' } : { items: [{ id: 999 }], page: 1, pageSize: 20, total: 1 })
    await operation
    if (name === 'statistics') expect(page.data.categoryGroups).toEqual([])
    expect(page.data.createdBy).toBe(0)
    expect(page.data.canSelectCreator).toBe(false)
    expect(name === 'statistics' ? page.data.summary : name === 'home' ? page.data.recentEntries : page.data.items).toEqual(name === 'statistics' ? null : [])
  })

  it.each(['account-list', 'category-list', 'account-edit', 'category-edit'])('%s denies ADMIN dictionary management and stale owner writes', async name => {
    const runtime = runtimeFor('ADMIN')
    const page = await loadPage(name, runtime)
    page.onLoad?.({})
    await page.onShow()
    expect(page.data.canManage).toBe(false)
    page.setData({ canManage: true, name: '现金', items: [{ id: 1, version: 1 }] })
    if (name.endsWith('edit')) await page.onSubmit()
    else { page.openCreate(); page.openEdit({ currentTarget: { dataset: { id: 1 } } }); await page.onDelete({ currentTarget: { dataset: { id: 1 } } }) }
    expect(wx.navigateTo).not.toHaveBeenCalled()
    expect(wx.showModal).not.toHaveBeenCalled()
    for (const key of ['createCategory', 'updateCategory', 'deleteCategory', 'createAccount', 'updateAccount', 'deleteAccount'] as const) expect(runtime.catalog[key]).not.toHaveBeenCalled()
  })

  it.each(['home', 'entry-list', 'statistics'])('%s clears selection and old results when session changes during a request', async name => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    await page.onShow()
    let resolve!: (value: any) => void
    const pending = new Promise(r => { resolve = r })
    if (name === 'statistics') runtime.statistics.summary.mockReturnValueOnce(pending)
    else runtime.entries.list.mockReturnValueOnce(pending)
    const operation = page.onCreator({ detail: { value: '1' } })
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    runtime.changeIdentity()
    resolve(name === 'statistics' ? summary : { items: [{ id: 99 }], page: 1, pageSize: 20, total: 1 })
    await operation
    if (name === 'statistics') expect(page.data.categoryGroups).toEqual([])
    expect(page.data.createdBy).toBe(0)
    expect(page.data.creators).toEqual([])
    expect(name === 'statistics' ? page.data.summary : name === 'home' ? page.data.recentEntries : page.data.items).toEqual(name === 'statistics' ? null : [])
  })

  it.each(['onDirectionChange', 'onDailyNext'])('reloads every personal statistic after demotion on %s', async action => {
    const runtime = runtimeFor()
    const page = await loadPage('statistics', runtime)
    await page.onShow()
    await page.onCreator({ detail: { value: '1' } })
    runtime.statistics.summary.mockClear()
    runtime.flow.refreshContext.mockImplementationOnce(async () => { runtime.setRole('ADMIN') })
    await page[action]({ detail: { value: '1' } })
    expect(page.data.createdBy).toBe(0)
    expect(page.data.dailyPage).toBe(1)
    expect(runtime.statistics.summary).toHaveBeenCalledOnce()
    expect(runtime.statistics.summary.mock.lastCall?.[0]).not.toHaveProperty('createdBy')
  })

  it('does not paint owner account balances if role changes while accounts load', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('account-list', runtime)
    let resolve!: (value: any) => void
    runtime.catalog.accounts.mockReturnValueOnce(new Promise(r => { resolve = r }))
    const operation = page.onShow()
    await Promise.resolve(); await Promise.resolve()
    runtime.setRole('ADMIN')
    resolve({ items: [{ id: 1, initialBalance: '100.00', currentBalance: '999.00' }] })
    await operation
    expect(page.data.items).toEqual([])
    expect(page.data.canManage).toBe(false)
  })

  it.each(['entry-detail', 'entry-edit'])('%s clears owner data and ignores a pending response after demotion', async name => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '5' })
    await page.onShow()
    let resolve!: (value: any) => void
    runtime.entries.detail.mockReturnValueOnce(new Promise(r => { resolve = r }))
    const operation = page.onShow()
    await Promise.resolve(); await Promise.resolve()
    runtime.setRole('ADMIN')
    resolve({ id: 5, createdBy: 2, amount: '888.00', canEdit: true, canDelete: true })
    await operation
    expect(page.data.canEdit).toBe(false)
    expect(name === 'entry-detail' ? page.data.entry : page.data.amount).toBe(name === 'entry-detail' ? null : '')
    if (name === 'entry-detail') { page.openEdit(); await page.onDelete() }
    else await page.onSubmit()
    expect(wx.navigateTo).not.toHaveBeenCalled()
    expect(runtime.entries.update).not.toHaveBeenCalled()
    expect(runtime.entries.remove).not.toHaveBeenCalled()
  })

  it.each([
    ['ADMIN', 'success'], ['ADMIN', 'failure'], ['MEMBER', 'success'], ['MEMBER', 'failure'],
  ])('clears foreign entry form after pending save %s demotion and %s response', async (role, result) => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '5' })
    await page.onShow()
    page.onAmountInput({ detail: { value: '123.45' } })
    page.onNoteInput({ detail: { value: '其他成员的备注' } })
    page.onPersonNameInput({ detail: { value: '其他成员的往来人' } })
    page.setData({ entryType: 'INCOME' })
    let resolve!: (value: any) => void
    let reject!: (reason: unknown) => void
    runtime.entries.update.mockReturnValueOnce(new Promise((r, j) => { resolve = r; reject = j }))
    const operation = page.onSubmit()
    expect(runtime.entries.update).toHaveBeenCalledOnce()
    runtime.setRole(role)
    expect(runtime.session.getRevision()).toBe(1)
    if (result === 'success') resolve({ id: 5 })
    else reject(new Error('save failed'))
    await operation
    expect(page.data).toMatchObject({ entryType: 'EXPENSE', amount: '', note: '', personName: '', categoryId: 0, accountId: 0,
      selectedCategoryName: '', selectedAccountName: '', entryDate: '', version: 0,
      categoryOptions: [], accountOptions: [], canEdit: false, canReload: false, busy: false, errorMessage: '' })
    expect(page._dirty).toBe(false)
    expect(wx.redirectTo).not.toHaveBeenCalled()
    await page.onSubmit()
    expect(runtime.entries.update).toHaveBeenCalledOnce()
  })

  it('checks current owner role for all catalog flow mutations while reads remain shared', async () => {
    const runtime = runtimeFor('ADMIN')
    const flow = new CatalogFlow(runtime.catalog as never, (() => runtime.session.getUser().role) as never)
    await flow.accounts()
    expect(runtime.catalog.accounts).toHaveBeenCalled()
    const writes: Array<() => Promise<unknown>> = [() => flow.createCategory({} as never), () => flow.updateCategory(1, {} as never), () => flow.deleteCategory(1), () => flow.createAccount({} as never), () => flow.updateAccount(1, {} as never), () => flow.deleteAccount(1, 1)]
    for (const write of writes) await expect(Promise.resolve().then(write)).rejects.toMatchObject({ code: 'ACCESS_DENIED' })
    runtime.setRole('OWNER')
    for (const write of writes) await write()
    expect(runtime.catalog.createAccount).toHaveBeenCalledOnce()
  })

  it.each(['ledger', 'members'])('%s accepts explicit unlimited null and rejects malformed limits', async kind => {
    const data = kind === 'ledger' ? ledger : { items: [], activeCount: 11, maxMembers: null, ownerUserId: 1 }
    const request = vi.fn().mockResolvedValue(data)
    const api = kind === 'ledger' ? () => new LedgerApi({ request } as never).getFixedLedger() : () => new MemberApi({ request } as never).list()
    expect(await api()).toMatchObject({ maxMembers: null })
    for (const maxMembers of [undefined, '10', 0, -1, 1.5]) {
      request.mockResolvedValueOnce({ ...data, maxMembers })
      await expect(api()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
  })
})
