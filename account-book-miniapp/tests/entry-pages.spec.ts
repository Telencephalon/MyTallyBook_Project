import { afterEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { EntryCreateIntent } from '../miniprogram/flows/entry-flow'
import { shanghaiToday } from '../miniprogram/utils/bookkeeping'
import { AppError } from '../miniprogram/types/error'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }
type PageName = 'entry-create' | 'entry-list' | 'entry-detail' | 'entry-edit'

const UUID = '11111111-2222-4333-8444-555555555555'
const category = { id: 7, entryType: 'EXPENSE', name: '餐饮', icon: null, color: null, sortNo: 0, systemDefault: false, status: 'ACTIVE' }
const account = { id: 8, name: '现金', accountType: 'CASH', initialBalance: '0.00', currentBalance: '0.00', sortNo: 0, status: 'ACTIVE', version: 0 }
const disabledCategory = { ...category, id: 70, name: '旧餐饮', status: 'DISABLED' }
const disabledAccount = { ...account, id: 80, name: '旧现金', status: 'DISABLED' }
const entry = {
  id: 40, entryType: 'EXPENSE', amount: '3.40', categoryId: 7, categoryName: '餐饮', categoryStatus: 'ACTIVE',
  accountId: 8, accountName: '现金', accountStatus: 'ACTIVE', entryDate: '2026-09-06', note: '晚餐',
  createdBy: 2, creatorName: '家庭成员', createdAt: '2026-09-06T01:00:00Z', updatedAt: '2026-09-06T01:00:00Z',
  version: 2, canEdit: true, canDelete: true, clientRequestId: UUID,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

function runtimeFor(uuidValues: string[] = [UUID]) {
  let revision = 1
  let uuidIndex = 0
  const nextUuid = () => uuidValues[uuidIndex++] ?? uuidValues[uuidValues.length - 1] ?? UUID
  const create = vi.fn().mockResolvedValue(entry)
  const intent = new EntryCreateIntent(create, nextUuid)
  return {
    session: { getRevision: () => revision, getUser: () => ({ userId: 2, role: 'MEMBER', nickname: '微信昵称', displayName: '家庭成员' as string | null }) },
    flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
    catalog: {
      categories: vi.fn().mockResolvedValue({ items: [category] }),
      accounts: vi.fn().mockResolvedValue({ items: [account] }),
    },
    entries: {
      list: vi.fn().mockResolvedValue({ items: [entry], page: 1, pageSize: 20, total: 1 }),
      detail: vi.fn().mockResolvedValue(entry),
      creators: vi.fn().mockResolvedValue({ items: [{ userId: 2, displayName: '家庭成员' }] }),
      create,
      update: vi.fn().mockResolvedValue(entry),
      remove: vi.fn().mockResolvedValue(undefined),
      newCreateIntent: vi.fn()
        .mockImplementationOnce(() => intent)
        .mockImplementation(() => new EntryCreateIntent(create, nextUuid)),
    },
    intent,
    setRevision(value: number) { revision = value },
  }
}

async function loadPage(name: PageName, runtime: ReturnType<typeof runtimeFor>, confirm = true) {
  let definition: PageShape | undefined
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', {
    navigateTo: vi.fn(), switchTab: vi.fn(), redirectTo: vi.fn(), navigateBack: vi.fn(),
    showToast: vi.fn(),
    enableAlertBeforeUnload: vi.fn((options: any) => options.success?.({ errMsg: 'enableAlertBeforeUnload:ok' })),
    disableAlertBeforeUnload: vi.fn(),
    showModal: vi.fn((options: any) => options.success({ confirm, cancel: !confirm })),
  })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import(`../miniprogram/pages/${name}/index.ts`)
  return {
    ...definition!, data: structuredClone(definition!.data),
    setData(this: PageShape, update: object) { Object.assign(this.data, update) },
  } as PageShape
}

afterEach(() => { vi.resetModules(); vi.clearAllMocks(); vi.unstubAllGlobals() })

describe('entry create page', () => {
  it('opens the favor shortcut with named defaults and submits a separate person name', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockResolvedValue({ items: [category, { ...category, id: 9, name: '人情' }] })
    runtime.catalog.accounts.mockResolvedValue({ items: [account, { ...account, id: 10, name: '微信' }] })
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'favor' }); await page.onShow()
    expect(page.data).toMatchObject({ entryType: 'EXPENSE', categoryId: 9, accountId: 10,
      categoryName: '人情', accountName: '微信', amount: '', note: '', personName: '', entryDate: shanghaiToday() })
    expect(runtime.entries.create).not.toHaveBeenCalled()
    page.onAmountInput({ detail: { value: '500' } })
    page.onPersonNameInput({ detail: { value: '  张三  ' } })
    page.onNoteInput({ detail: { value: '婚礼' } })
    await page.onSubmit()
    expect(runtime.entries.create).toHaveBeenCalledWith(expect.objectContaining({
      personName: '张三', note: '婚礼', categoryId: 9, accountId: 10, amount: '500',
    }))
  })

  it('opens the life shortcut with the life category selected', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockResolvedValue({ items: [{ ...category, id: 11, name: '生活' }] })
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'life' }); await page.onShow()
    expect(page.data.categoryName).toBe('生活')
    expect(page.data.categoryId).toBe(11)
    expect(page.data.personName).toBe('家庭成员')
  })

  it.each([null, '', '   '])('defaults life person name to nickname when display name is %s', async displayName => {
    const runtime = runtimeFor()
    vi.spyOn(runtime.session, 'getUser').mockReturnValue({ userId: 2, role: 'MEMBER', nickname: '微信昵称', displayName })
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'life' }); await page.onShow()
    expect(page.data.personName).toBe('微信昵称')
  })

  it.each(['自填人名', ''])('preserves life person name edit %s across page return and dictionary reload', async personName => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'life' }); await page.onShow()
    page.onPersonNameInput({ detail: { value: personName } })
    page.onHide(); await page.onShow()
    await page.loadDictionaries()
    expect(page.data.personName).toBe(personName)
  })

  it('uses refreshed creator identity and resets the life default after an identity switch', async () => {
    const runtime = runtimeFor()
    const getUser = vi.spyOn(runtime.session, 'getUser')
    runtime.flow.refreshContext.mockImplementationOnce(async () => {
      getUser.mockReturnValue({ userId: 2, role: 'MEMBER', nickname: '微信昵称', displayName: '最新姓名' })
    })
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'life' }); await page.onShow()
    expect(page.data.personName).toBe('最新姓名')
    page.onPersonNameInput({ detail: { value: '手工修改' } })
    runtime.setRevision(2)
    getUser.mockReturnValue({ userId: 3, role: 'MEMBER', nickname: '另一昵称', displayName: '另一成员' })
    await page.onShow()
    expect(page.data.personName).toBe('另一成员')
  })

  it('submits the default life creator name as the separate person field', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockResolvedValue({ items: [{ ...category, name: '生活' }] })
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'life' }); await page.onShow()
    page.onAmountInput({ detail: { value: '12' } })
    await page.onSubmit()
    expect(runtime.entries.create).toHaveBeenCalledWith(expect.objectContaining({ personName: '家庭成员', note: null }))
  })

  it('keeps ordinary entry person name empty', async () => {
    const page = await loadPage('entry-create', runtimeFor())
    page.onLoad(); await page.onShow()
    expect(page.data.personName).toBe('')
  })

  it('leaves missing favor defaults unselected and preserves later user choices', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-create', runtime)
    page.onLoad({ preset: 'favor' }); await page.onShow()
    expect(page.data).toMatchObject({ categoryId: 0, accountId: 0 })
    page.onCategoryChange({ detail: { value: 0 } })
    page.onAccountChange({ detail: { value: 0 } })
    page.onPersonNameInput({ detail: { value: '李四' } })
    page.onHide(); await page.onShow()
    expect(page.data).toMatchObject({ categoryId: 7, accountId: 8, personName: '李四' })
    runtime.setRevision(2)
    await page.onShow()
    expect(page.data.personName).toBe('')
  })

  it('loads and updates the person name without merging it into the note', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockResolvedValue({ ...entry, personName: '张三' })
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    expect(page.data.personName).toBe('张三')
    page.onPersonNameInput({ detail: { value: '李四' } })
    await page.onSubmit()
    expect(runtime.entries.update).toHaveBeenCalledWith(40, expect.objectContaining({ personName: '李四', note: '晚餐' }))
  })
  it('renders runtime configuration failures at page entry without throwing', async () => {
    const { AppError } = await import('../miniprogram/types/error')
    let definition: PageShape | undefined
    const request = vi.fn()
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { request, switchTab: vi.fn(), redirectTo: vi.fn(), navigateBack: vi.fn() })
    vi.doMock('../miniprogram/runtime', () => ({
      getRuntime: () => { throw new AppError('CONFIG', 'API_DOMAIN_INVALID', 'API 地址必须是备案后的 HTTPS 域名') },
    }))
    await import('../miniprogram/pages/entry-create/index')
    const page = {
      ...definition!, data: structuredClone(definition!.data),
      setData(this: PageShape, update: object) { Object.assign(this.data, update) },
    } as PageShape

    expect(() => page.onLoad()).not.toThrow()
    await page.onShow()

    expect(page.data.errorMessage).toBe('API 地址必须是备案后的 HTTPS 域名')
    expect(request).not.toHaveBeenCalled()
  })

  it('defaults the business date in Asia/Shanghai rather than UTC', () => {
    expect(shanghaiToday(Date.parse('2026-09-05T16:30:00Z'))).toBe('2026-09-06')
  })

  it('shows selected active category and account names on the create form', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow()

    expect(page.data).toMatchObject({ categoryName: '餐饮', accountName: '现金' })
    expect(runtime.catalog.categories).toHaveBeenCalledWith(undefined, 'ACTIVE')
    expect(runtime.catalog.accounts).toHaveBeenCalledWith('ACTIVE')
  })

  it('retries failed dictionaries independently while preserving the create draft', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockRejectedValueOnce(new Error('categories offline'))
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow()
    page.onAmountInput({ detail: { value: '8.80' } })
    page.onNoteInput({ detail: { value: '保留备注' } })
    page.onDateChange({ detail: { value: '2026-09-10' } })

    expect(page.data.canRetryRead).toBe(true)
    await page.retryDictionaries()

    expect(runtime.catalog.categories).toHaveBeenCalledTimes(2)
    expect(runtime.catalog.accounts).toHaveBeenCalledTimes(2)
    expect(runtime.entries.create).not.toHaveBeenCalled()
    expect(page.data).toMatchObject({
      amount: '8.80',
      note: '保留备注',
      entryDate: '2026-09-10',
      canRetryRead: false,
    })
  })

  it('retains exact edits and offers manual retry after an unknown result', async () => {
    const runtime = runtimeFor()
    runtime.entries.create.mockRejectedValueOnce({ kind: 'TIMEOUT', code: 'REQUEST_TIMEOUT', message: '请求超时' })
      .mockResolvedValueOnce(entry)
    const page = await loadPage('entry-create', runtime)
    page.onLoad()
    await page.onShow()
    page.onAmountInput({ detail: { value: '3.40' } })
    page.onNoteInput({ detail: { value: '原样备注 ' } })

    await page.onSubmit()
    expect(page.data.canRetry).toBe(true)
    expect(page.data.amount).toBe('3.40')
    await page.onRetry()

    expect(runtime.entries.create.mock.calls[1][0]).toEqual(runtime.entries.create.mock.calls[0][0])
    expect(wx.switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })

  it('preserves the complete create form after a direct network failure', async () => {
    const runtime = runtimeFor()
    const { AppError: RuntimeAppError } = await import('../miniprogram/types/error')
    runtime.entries.create.mockRejectedValueOnce(new RuntimeAppError('NETWORK', 'NETWORK_ERROR', '网络连接失败', 'req-create-network'))
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow()
    page.onAmountInput({ detail: { value: '3.40' } })
    page.onNoteInput({ detail: { value: '原样备注' } })
    page.onCategoryChange({ detail: { value: '0' } })
    page.onAccountChange({ detail: { value: '0' } })
    page.onDateChange({ detail: { value: '2026-09-09' } })
    const before = { amount: page.data.amount, note: page.data.note, categoryId: page.data.categoryId,
      accountId: page.data.accountId, entryDate: page.data.entryDate }

    await page.onSubmit()

    expect(page.data).toMatchObject({ ...before, busy: false, canRetry: true })
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.requestId).toBe('req-create-network')
    expect(runtime.entries.create).toHaveBeenCalledTimes(1)
  })

  it('prompts before abandoning an unknown result and creates no persistent draft', async () => {
    const runtime = runtimeFor()
    runtime.entries.create.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    vi.mocked(wx.disableAlertBeforeUnload).mockClear()
    await page.onSubmit()
    await page.onAbandon()
    expect(wx.showModal).toHaveBeenCalledWith(expect.objectContaining({ title: expect.stringMatching(/放弃/) }))
    expect(wx.navigateBack).toHaveBeenCalledTimes(1)
  })

  it('ignores a submit completion after hide or logout and releases busy when shown again', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor()
    runtime.entries.create.mockReturnValueOnce(pending.promise)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()
    page.onHide(); runtime.setRevision(2); await page.onShow()
    pending.resolve(entry); await operation
    expect(page.data.busy).toBe(false)
    expect(wx.switchTab).not.toHaveBeenCalled()
    expect(wx.disableAlertBeforeUnload).toHaveBeenCalled()
  })

  it('reconciles a successful write completed while hidden without creating a second write', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor(); runtime.entries.create.mockReturnValueOnce(pending.promise)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()
    await vi.waitFor(() => expect(runtime.entries.create).toHaveBeenCalledTimes(1))

    page.onHide(); pending.resolve(entry); await operation
    expect(wx.switchTab).not.toHaveBeenCalled()
    await page.onShow()

    expect(wx.switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
    expect(runtime.entries.create).toHaveBeenCalledTimes(1)
    expect(runtime.entries.newCreateIntent).toHaveBeenCalledTimes(1)
  })

  it('restores an ambiguous hidden failure for explicit same-payload retry', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor(); runtime.entries.create.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(entry)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()
    await vi.waitFor(() => expect(runtime.entries.create).toHaveBeenCalledTimes(1))

    page.onHide()
    pending.reject(new AppError('HTTP', 'HTTP_ERROR', '网关异常', 'req-a', 502))
    await operation; await page.onShow(); await page.onRetry()

    expect(runtime.entries.create.mock.calls[1][0]).toEqual(runtime.entries.create.mock.calls[0][0])
    expect(runtime.entries.newCreateIntent).toHaveBeenCalledTimes(1)
  })

  it('allows a fresh UUID after a definitive hidden failure in the same identity', async () => {
    const pending = deferred<typeof entry>()
    const secondUuid = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
    const runtime = runtimeFor([UUID, secondUuid])
    runtime.entries.create.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(entry)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()
    await vi.waitFor(() => expect(runtime.entries.create).toHaveBeenCalledTimes(1))

    page.onHide()
    pending.reject(new AppError('HTTP', 'VALIDATION_FAILED', '请求参数不正确', 'req-a', 400))
    await operation; await page.onShow(); await page.onSubmit()

    expect(runtime.entries.create.mock.calls[0][0].clientRequestId).toBe(UUID)
    expect(runtime.entries.create.mock.calls[1][0].clientRequestId).toBe(secondUuid)
    expect(runtime.entries.newCreateIntent).toHaveBeenCalledTimes(1)
  })

  it('discards a hidden completed result after identity change without stale navigation', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor(); runtime.entries.create.mockReturnValueOnce(pending.promise)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()
    await vi.waitFor(() => expect(runtime.entries.create).toHaveBeenCalledTimes(1))

    page.onHide(); pending.resolve(entry); await operation
    runtime.setRevision(2); await page.onShow()

    expect(wx.switchTab).not.toHaveBeenCalled()
    expect(page.data).toMatchObject({ amount: '', canRetry: false, busy: false })
    expect(runtime.entries.newCreateIntent).toHaveBeenCalledTimes(2)
  })

  it('locks all form mutations during a pending request without invalidating its completion guard', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor(); runtime.entries.create.mockReturnValueOnce(pending.promise)
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    const operation = page.onSubmit()

    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    page.onAmountInput({ detail: { value: '9.99' } })
    page.onNoteInput({ detail: { value: 'changed' } })
    expect(page.data).toMatchObject({ entryType: 'EXPENSE', amount: '3.40', note: '', busy: true })
    expect(runtime.catalog.categories).toHaveBeenCalledTimes(1)
    expect(wx.enableAlertBeforeUnload).toHaveBeenCalledTimes(1)

    pending.resolve(entry); await operation
    expect(page.data.busy).toBe(false)
    expect(wx.disableAlertBeforeUnload).toHaveBeenCalled()
    expect(wx.switchTab).toHaveBeenCalledWith({ url: '/pages/home/index' })
  })

  it('does not start a write when native departure-warning activation fails', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    vi.mocked(wx.enableAlertBeforeUnload).mockImplementationOnce((options: any) => {
      options.fail?.({ errMsg: 'enableAlertBeforeUnload:fail unsupported' })
    })

    await page.onSubmit()

    expect(runtime.entries.create).not.toHaveBeenCalled()
    expect(page.data.busy).toBe(false)
    expect(page.data.canRetry).toBe(false)
    expect(page.data.errorMessage).toMatch(/离开提醒/)
  })

  it('locks visible fields and ordinary save after an ambiguous result until confirmed abandon', async () => {
    const runtime = runtimeFor()
    runtime.entries.create.mockRejectedValueOnce(new AppError('HTTP', 'HTTP_ERROR', '网关异常', 'req-a', 502))
    const page = await loadPage('entry-create', runtime)
    page.onLoad(); await page.onShow(); page.onAmountInput({ detail: { value: '3.40' } })
    vi.mocked(wx.disableAlertBeforeUnload).mockClear()
    await page.onSubmit()

    page.onAmountInput({ detail: { value: '9.99' } })
    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    await page.onSubmit()

    expect(page.data).toMatchObject({ entryType: 'EXPENSE', amount: '3.40', canRetry: true })
    expect(runtime.entries.create).toHaveBeenCalledTimes(1)
    expect(wx.disableAlertBeforeUnload).not.toHaveBeenCalled()
    await page.onAbandon()
    expect(wx.disableAlertBeforeUnload).toHaveBeenCalledTimes(1)
    expect(wx.navigateBack).toHaveBeenCalledTimes(1)
  })
})
describe.each(['entry-create', 'entry-edit'] as const)('%s direct type selection', name => {
  it('switches directly in both directions and preserves other form fields', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockResolvedValue({ items: [category,
      { ...category, id: 9, name: '工资', entryType: 'INCOME' },
      { ...category, id: 10, name: '其他', entryType: 'BOTH' }] })
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    runtime.catalog.categories.mockClear()
    runtime.catalog.accounts.mockClear()
    runtime.flow.refreshContext.mockClear()
    page.setData({ canEdit: true, loading: false, categoryId: 7, amount: '18.50', personName: '张三', note: '午餐' })
    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    expect(page.data).toMatchObject({ entryType: 'INCOME', amount: '18.50', personName: '张三', note: '午餐' })
    expect((page.data.categories ?? page.data.categoryOptions).map((item: { id: number }) => item.id)).toEqual([9, 10])
    page.onTypeChange({ currentTarget: { dataset: { type: 'EXPENSE' } } })
    expect(page.data.entryType).toBe('EXPENSE')
    expect(page.data.loading).toBe(false)
    expect((page.data.categories ?? page.data.categoryOptions).map((item: { id: number }) => item.id)).toEqual([7, 10])
    expect(runtime.catalog.categories).not.toHaveBeenCalled()
    expect(runtime.catalog.accounts).not.toHaveBeenCalled()
    expect(runtime.flow.refreshContext).not.toHaveBeenCalled()
    expect(runtime.entries.create).not.toHaveBeenCalled()
    expect(runtime.entries.update).not.toHaveBeenCalled()
    expect(wx.showModal).not.toHaveBeenCalled()
  })

  it('preserves the selected category on repeated taps and ignores invalid or locked changes', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    runtime.catalog.categories.mockClear()
    page.setData({ canEdit: true, loading: false, categoryId: 7 })
    await page.onTypeChange({ currentTarget: { dataset: { type: 'EXPENSE' } } })
    await page.onTypeChange({ currentTarget: { dataset: { type: 'INVALID' } } })
    page.setData({ busy: true })
    await page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    expect(page.data).toMatchObject({ entryType: 'EXPENSE', categoryId: 7 })
    expect(runtime.catalog.categories).not.toHaveBeenCalled()
  })

  it('does not reuse categories after leaving or changing identity', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    page.onHide()
    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    expect(page.data.entryType).toBe('EXPENSE')
    await page.onShow()
    runtime.setRevision(2)
    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    expect(page.data.entryType).toBe('EXPENSE')
  })

  it('clears incompatible categories when the destination type has none', async () => {
    const runtime = runtimeFor()
    const page = await loadPage(name, runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    page.onTypeChange({ currentTarget: { dataset: { type: 'INCOME' } } })
    expect(page.data.categoryId).toBe(0)
    expect(page.data.categories ?? page.data.categoryOptions).toEqual([])
    expect(page.data.loading).toBe(false)
  })
})

describe('entry list/detail/edit pages', () => {
  function installNavigationStack(urls: string[]) {
    const stackPage = (url: string) => {
      const [path, query = ''] = url.split('?')
      return { route: path.replace(/^\//, ''), options: Object.fromEntries(new URLSearchParams(query)) }
    }
    const pages = urls.map(stackPage)
    vi.stubGlobal('getCurrentPages', () => pages.slice())
    vi.mocked(wx.navigateTo).mockImplementation((options: any) => {
      pages.push(stackPage(options.url)); options.success?.({ errMsg: 'navigateTo:ok' })
    })
    vi.mocked(wx.redirectTo).mockImplementation((options: any) => {
      pages.splice(-1, 1, stackPage(options.url)); options.success?.({ errMsg: 'redirectTo:ok' })
    })
    vi.mocked(wx.navigateBack).mockImplementation((options: any = {}) => {
      pages.splice(Math.max(1, pages.length - (options.delta || 1)))
      options.success?.({ errMsg: 'navigateBack:ok' })
    })
    return pages
  }

  it.each(['home', 'entry-list'])('returns to the original %s with one back after twelve edit/save cycles', async origin => {
    const runtime = runtimeFor()
    let saved = { ...entry }
    runtime.entries.detail.mockImplementation(async () => saved)
    runtime.entries.update.mockImplementation(async (_id, body) => {
      saved = { ...saved, ...body, version: saved.version + 1 }
      return saved
    })
    const detail = await loadPage('entry-detail', runtime)
    const editorTemplate = await loadPage('entry-edit', runtime)
    const pages = installNavigationStack([`/pages/${origin}/index`, '/pages/entry-detail/index?id=40'])
    detail.onLoad({ id: '40' }); await detail.onShow()

    for (let index = 0; index < 12; index++) {
      detail.openEdit()
      expect(pages).toHaveLength(3)
      detail.onHide()
      const editor = { ...editorTemplate, data: structuredClone(editorTemplate.data) } as PageShape
      editor.onLoad({ id: '40' }); await editor.onShow()
      editor.onNoteInput({ detail: { value: `第 ${index + 1} 次修改` } })
      await editor.onSubmit()
      expect(pages.map(page => page.route)).toEqual([`pages/${origin}/index`, 'pages/entry-detail/index'])
      editor.onUnload()
      await detail.onShow()
      expect(detail.data.entry.note).toBe(`第 ${index + 1} 次修改`)
      expect(detail.data.entry.version).toBe(entry.version + index + 1)
    }
    wx.navigateBack({ delta: 1 })
    expect(pages.map(page => page.route)).toEqual([`pages/${origin}/index`])
    expect(wx.redirectTo).not.toHaveBeenCalled()
    expect(wx.switchTab).not.toHaveBeenCalled()
  })

  it('collapses consecutive old copies of this detail page after saving', async () => {
    const page = await loadPage('entry-edit', runtimeFor())
    const pages = installNavigationStack(['/pages/home/index',
      '/pages/entry-detail/index?id=40', '/pages/entry-detail/index?id=40',
      '/pages/entry-detail/index?id=40', '/pages/entry-edit/index?id=40'])
    page.onLoad({ id: '40' }); await page.onShow(); await page.onSubmit()
    expect(pages.map(item => item.route)).toEqual(['pages/home/index', 'pages/entry-detail/index'])
    expect(wx.navigateBack).toHaveBeenCalledWith(expect.objectContaining({ delta: 3 }))
  })

  it.each([
    ['/pages/entry-list/index', '/pages/entry-edit/index?id=40'],
    ['/pages/home/index', '/pages/entry-detail/index?id=17', '/pages/entry-edit/index?id=40'],
  ])('replaces a direct editor without returning to an unrelated bill: %j', async (...urls) => {
    const page = await loadPage('entry-edit', runtimeFor())
    const pages = installNavigationStack(urls)
    page.onLoad({ id: '40' }); await page.onShow(); await page.onSubmit()
    expect(pages).toHaveLength(urls.length)
    expect(pages[pages.length - 1]).toEqual({ route: 'pages/entry-detail/index', options: { id: '40' } })
    expect(wx.navigateBack).not.toHaveBeenCalled()
  })

  it('ignores rapid repeated edit taps and unlocks when the detail is shown again', async () => {
    const page = await loadPage('entry-detail', runtimeFor())
    page.onLoad({ id: '40' }); await page.onShow()
    page.openEdit(); page.openEdit(); page.openEdit()
    expect(wx.navigateTo).toHaveBeenCalledOnce()
    page.onHide(); await page.onShow(); page.openEdit()
    expect(wx.navigateTo).toHaveBeenCalledTimes(2)
  })

  it('allows retry when opening the editor fails, but blocks editing while loading or hidden', async () => {
    const page = await loadPage('entry-detail', runtimeFor())
    page.onLoad({ id: '40' }); await page.onShow()
    vi.mocked(wx.navigateTo).mockImplementationOnce((options: any) => options.fail?.({ errMsg: 'navigateTo:fail' }))
    page.openEdit(); page.openEdit()
    expect(wx.navigateTo).toHaveBeenCalledTimes(2)
    page.onHide(); page.openEdit()
    await page.onShow(); page.setData({ loading: true }); page.openEdit()
    expect(wx.navigateTo).toHaveBeenCalledTimes(2)
  })

  it('quick filter controls do not inherit the full-panel toggle', () => {
    const wxml = readFileSync(new URL('../miniprogram/pages/entry-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml.match(/<view class="filter-toggle"[^>]*>/)?.[0]).not.toContain('bindtap=')
    for (const handler of ['onQuickCategory', 'onQuickType', 'onCreator']) {
      expect(wxml).toContain(`bindchange="${handler}"`)
    }
    expect(wxml).toContain('bindtap="toggleDateFilter"')
  })

  it('quick category and type immediately refresh only their selection and reset pagination', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-list', runtime)
    await page.onShow()
    page.setData({ page: 3, keyword: '午餐', accountId: 8 })
    await page.onQuickCategory({ detail: { value: '1' } })
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: 7, page: 1, keyword: '午餐', accountId: 8 }))
    expect(page.data.filtersExpanded).toBe(false)
    await page.onQuickType({ detail: { value: '2' } })
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ entryType: 'INCOME', categoryId: 7, page: 1 }))
    await page.onQuickCategory({ detail: { value: '0' } })
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ categoryId: undefined, entryType: 'INCOME', accountId: 8 }))
    await page.onQuickType({ detail: { value: '0' } })
    expect(runtime.entries.list.mock.lastCall?.[0]?.entryType).toBeUndefined()
    expect(runtime.catalog.categories).toHaveBeenCalledTimes(1)
  })

  it('ignores invalid and duplicate quick selections', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-list', runtime)
    await page.onShow()
    runtime.entries.list.mockClear()
    for (const value of ['-1', '1.5', '100', 'bad', '0']) {
      await page.onQuickType({ detail: { value } })
      await page.onQuickCategory({ detail: { value } })
    }
    expect(runtime.entries.list).not.toHaveBeenCalled()
    expect(page.data).toMatchObject({ entryType: '', categoryId: 0, filtersExpanded: false })
  })

  it('opens only date controls and applies its draft on confirmation', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-list', runtime)
    await page.onShow()
    page.setData({ page: 3, categoryId: 7 })
    runtime.entries.list.mockClear()
    page.toggleDateFilter()
    expect(page.data).toMatchObject({ dateFilterExpanded: true, filtersExpanded: false })
    page.onDateDraftFrom({ detail: { value: '2026-09-01' } })
    page.onDateDraftTo({ detail: { value: '2026-09-22' } })
    expect(page.data.dateFrom).toBe('')
    expect(runtime.entries.list).not.toHaveBeenCalled()
    await page.applyDateFilter()
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ dateFrom: '2026-09-01', dateTo: '2026-09-22', categoryId: 7, page: 1 }))
    expect(page.data.dateFilterExpanded).toBe(false)
    page.toggleDateFilter()
    await page.clearDateFilter()
    expect(runtime.entries.list).toHaveBeenLastCalledWith(expect.objectContaining({ dateFrom: undefined, dateTo: undefined, categoryId: 7 }))
  })

  it('rejects reversed dates and discards date drafts when cancelled or advanced filters open', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-list', runtime)
    await page.onShow()
    runtime.entries.list.mockClear()
    page.toggleDateFilter()
    page.onDateDraftFrom({ detail: { value: '2026-09-22' } })
    page.onDateDraftTo({ detail: { value: '2026-09-01' } })
    await page.applyDateFilter()
    expect(page.data.dateFilterError).toBe('开始日期不能晚于结束日期')
    expect(runtime.entries.list).not.toHaveBeenCalled()
    page.toggleDateFilter()
    expect(page.data).toMatchObject({ dateFilterExpanded: false, dateFrom: '', dateTo: '' })
    page.toggleDateFilter()
    expect(page.data).toMatchObject({ dateDraftFrom: '', dateDraftTo: '', dateFilterError: '' })
    page.toggleFilters()
    expect(page.data).toMatchObject({ dateFilterExpanded: false, filtersExpanded: true })
    expect(runtime.entries.list).not.toHaveBeenCalled()
  })

  it('entry-list exposes a read retry after a failed load', async () => {
    const runtime = runtimeFor()
    runtime.entries.list.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage('entry-list', runtime)

    await page.onShow()

    expect(page.data.loadState).toBe('error')
    expect(typeof page.retry).toBe('function')
    const wxml = readFileSync(new URL('../miniprogram/pages/entry-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="retry"')
    await page.retry()
    expect(runtime.entries.list).toHaveBeenCalledTimes(2)
  })

  it('keeps list filters collapsed until the filter header is tapped', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-list', runtime)

    expect(page.data.filtersExpanded).toBe(false)
    page.toggleFilters()
    expect(page.data.filtersExpanded).toBe(true)
    page.toggleFilters()
    expect(page.data.filtersExpanded).toBe(false)

    const wxml = readFileSync(new URL('../miniprogram/pages/entry-list/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="toggleFilters"')
    expect(wxml).toContain('wx:if="{{filtersExpanded}}"')
  })

  it('entry-detail exposes a read retry after a failed load', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockRejectedValueOnce(new Error('offline'))
    const page = await loadPage('entry-detail', runtime)
    page.onLoad({ id: '40' })

    await page.onShow()

    expect(page.data.errorMessage).not.toBe('')
    expect(typeof page.onRetry).toBe('function')
    const wxml = readFileSync(new URL('../miniprogram/pages/entry-detail/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toContain('bindtap="onRetry"')
    await page.onRetry()
    expect(runtime.entries.detail).toHaveBeenCalledTimes(2)
  })

  it('renders runtime configuration failures from the edit entry boundary', async () => {
    let definition: PageShape | undefined
    const request = vi.fn()
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { request, switchTab: vi.fn(), redirectTo: vi.fn(), navigateBack: vi.fn(), showModal: vi.fn() })
    vi.doMock('../miniprogram/runtime', () => ({
      getRuntime: () => { throw new AppError('CONFIG', 'API_DOMAIN_INVALID', 'API 地址必须是备案后的 HTTPS 域名') },
    }))
    await import('../miniprogram/pages/entry-edit/index')
    const page = {
      ...definition!, data: structuredClone(definition!.data),
      setData(this: PageShape, update: object) { Object.assign(this.data, update) },
    } as PageShape

    page.onLoad({ id: '40' })
    await expect(page.onShow()).resolves.toBeUndefined()
    expect(page.data.errorMessage).toBe('操作失败，请稍后重试')
    expect(request).not.toHaveBeenCalled()
  })

  it('list applies every filter, resets pagination, and loads historical creators', async () => {
    const runtime = runtimeFor()
    vi.spyOn(runtime.session, 'getUser').mockReturnValue({ userId: 2, role: 'OWNER', nickname: '所有者', displayName: null })
    const page = await loadPage('entry-list', runtime)
    await page.onShow()
    page.setData({ page: 3, dateFrom: '2026-09-01', dateTo: '2026-09-06', entryType: 'EXPENSE', categoryId: 7, accountId: 8, createdBy: 2, keyword: '%_\\' })
    await page.onApplyFilters()
    expect(runtime.entries.list).toHaveBeenLastCalledWith({ dateFrom: '2026-09-01', dateTo: '2026-09-06', entryType: 'EXPENSE', categoryId: 7, accountId: 8, createdBy: 2, keyword: '%_\\', page: 1, pageSize: 20 })
    expect(runtime.entries.creators).toHaveBeenCalled()
  })

  it('loads disabled resources for historical filters and labels their status', async () => {
    const runtime = runtimeFor()
    runtime.catalog.categories.mockResolvedValueOnce({ items: [category, disabledCategory] })
    runtime.catalog.accounts.mockResolvedValueOnce({ items: [account, disabledAccount] })
    const page = await loadPage('entry-list', runtime)
    await page.onShow()

    expect(page.data.categories).toContainEqual(expect.objectContaining({ id: 70, displayName: '旧餐饮（已停用）' }))
    expect(page.data.accounts).toContainEqual(expect.objectContaining({ id: 80, displayName: '旧现金（已停用）' }))
    expect(runtime.catalog.categories).toHaveBeenCalledWith(undefined, undefined)
    expect(runtime.catalog.accounts).toHaveBeenCalledWith(undefined)
  })

  it('exposes exclusive loading, successful-empty, and error states and clears stale rows on failure', async () => {
    const runtime = runtimeFor()
    const pendingList = deferred<{ items: typeof entry[]; page: number; pageSize: number; total: number }>()
    runtime.entries.list.mockReturnValueOnce(pendingList.promise)
    const page = await loadPage('entry-list', runtime)
    const pending = page.onShow()
    await vi.waitFor(() => expect(runtime.entries.list).toHaveBeenCalledTimes(1))
    expect(page.data.loadState).toBe('loading')

    pendingList.resolve({ items: [], page: 1, pageSize: 20, total: 0 })
    await pending
    expect(page.data).toMatchObject({ loadState: 'empty', items: [], total: 0, errorMessage: '' })

    page.setData({ items: [entry], total: 1 })
    runtime.entries.list.mockRejectedValueOnce(new Error('offline'))
    await page.onApplyFilters()
    expect(page.data).toMatchObject({ loadState: 'error', items: [], total: 0 })
    expect(page.data.errorMessage).not.toBe('')
  })

  it('detail honors server controls', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockResolvedValueOnce({ ...entry, canEdit: false, canDelete: false })
    const page = await loadPage('entry-detail', runtime)
    page.onLoad({ id: '40' }); await page.onShow(); await page.onDelete()
    expect(page.data.canEdit).toBe(false)
    expect(runtime.entries.remove).not.toHaveBeenCalled()
    expect(wx.showModal).not.toHaveBeenCalled()
  })

  it('authorized detail deletion cancellation opens the modal and never deletes', async () => {
    const runtime = runtimeFor()
    const page = await loadPage('entry-detail', runtime, false)
    page.onLoad({ id: '40' }); await page.onShow(); await page.onDelete()
    expect(page.data.canDelete).toBe(true)
    expect(wx.showModal).toHaveBeenCalledTimes(1)
    expect(runtime.entries.remove).not.toHaveBeenCalled()
    expect(page.data.busy).toBe(false)
  })

  it('entry-detail keeps a hidden pending deletion locked until it settles', async () => {
    const pendingDelete = deferred<void>()
    const runtime = runtimeFor()
    runtime.entries.remove.mockReturnValueOnce(pendingDelete.promise)
    const page = await loadPage('entry-detail', runtime)
    page.onLoad({ id: '40' }); await page.onShow()

    const pending = page.onDelete()
    await Promise.resolve()
    page.onHide()
    await page.onShow()
    await page.onDelete()

    expect(runtime.entries.remove).toHaveBeenCalledTimes(1)
    expect(page.data.busy).toBe(true)
    pendingDelete.resolve()
    await pending

    expect(page.data.busy).toBe(false)
    expect(wx.navigateTo).not.toHaveBeenCalled()
  })

  it('edit retains local values on conflict and reloads only after confirmation', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockResolvedValueOnce(entry).mockResolvedValueOnce({ ...entry, amount: '4.20', version: 3 })
    runtime.entries.update.mockRejectedValueOnce({ code: 'ENTRY_VERSION_CONFLICT', message: '账单已变化' })
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow(); page.onAmountInput({ detail: { value: '9.99' } })
    await page.onSubmit()
    expect(page.data.amount).toBe('9.99')
    expect(page.data.canReload).toBe(true)
    await page.onReload()
    expect(page.data.amount).toBe('4.20')
    expect(page.data.version).toBe(3)
  })

  it('preserves the complete edit form after a direct network failure', async () => {
    const runtime = runtimeFor()
    const { AppError: RuntimeAppError } = await import('../miniprogram/types/error')
    runtime.entries.update.mockRejectedValueOnce(new RuntimeAppError('NETWORK', 'NETWORK_ERROR', '网络连接失败', 'req-edit-network'))
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    page.onAmountInput({ detail: { value: '9.99' } })
    page.onNoteInput({ detail: { value: '修改备注' } })
    page.onCategoryChange({ detail: { value: '0' } })
    page.onAccountChange({ detail: { value: '0' } })
    page.onDateChange({ detail: { value: '2026-09-09' } })
    const before = { amount: page.data.amount, note: page.data.note, categoryId: page.data.categoryId,
      accountId: page.data.accountId, entryDate: page.data.entryDate }

    await page.onSubmit()

    expect(page.data).toMatchObject({ ...before, busy: false, canReload: false })
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.requestId).toBe('req-edit-network')
    expect(runtime.entries.update).toHaveBeenCalledTimes(1)
    expect(wx.switchTab).not.toHaveBeenCalled()
  })

  it('entry-edit keeps a hidden pending save locked until it settles', async () => {
    const pendingUpdate = deferred<typeof entry>()
    const runtime = runtimeFor()
    runtime.entries.update.mockReturnValueOnce(pendingUpdate.promise)
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    page.onAmountInput({ detail: { value: '9.99' } })

    const pending = page.onSubmit()
    await Promise.resolve()
    page.onHide()
    await page.onShow()
    await page.onSubmit()

    expect(runtime.entries.update).toHaveBeenCalledTimes(1)
    expect(page.data.busy).toBe(true)
    pendingUpdate.resolve(entry)
    await pending

    expect(page.data.busy).toBe(false)
    expect(wx.switchTab).not.toHaveBeenCalled()
  })

  it('edit preserves the conflict message and reload action across hide/show', async () => {
    const runtime = runtimeFor()
    runtime.entries.update.mockRejectedValueOnce(
      new AppError('HTTP', 'ENTRY_VERSION_CONFLICT', '账单已变化', 'req-conflict', 409),
    )
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow(); page.onAmountInput({ detail: { value: '9.99' } })
    await page.onSubmit()
    const conflictMessage = page.data.errorMessage
    page.onHide(); await page.onShow()

    expect(page.data.amount).toBe('9.99')
    expect(conflictMessage).not.toBe('')
    expect(page.data.errorMessage).toBe(conflictMessage)
    expect(page.data.canReload).toBe(true)
    expect(runtime.entries.detail).toHaveBeenCalledTimes(1)
  })

  it('direct edit route respects a server canEdit denial in both data and submit handler', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockResolvedValueOnce({ ...entry, canEdit: false, canDelete: false })
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow(); page.onAmountInput({ detail: { value: '9.99' } })
    await page.onSubmit()

    expect(page.data.canEdit).toBe(false)
    expect(runtime.entries.update).not.toHaveBeenCalled()
  })

  it('edit includes original disabled associations without making them general active options', async () => {
    const runtime = runtimeFor()
    runtime.entries.detail.mockResolvedValueOnce({ ...entry, categoryId: 70, categoryName: '旧分类', categoryStatus: 'DISABLED', accountId: 80, accountName: '旧账户', accountStatus: 'DISABLED' })
    const page = await loadPage('entry-edit', runtime)
    page.onLoad({ id: '40' }); await page.onShow()
    expect(page.data.categoryOptions).toContainEqual({ id: 70, name: '旧分类（已停用）', status: 'DISABLED', original: true })
    expect(page.data.accountOptions).toContainEqual({ id: 80, name: '旧账户（已停用）', status: 'DISABLED', original: true })
    expect(page.data.selectedCategoryName).toBe('旧分类（已停用）')
    expect(page.data.selectedAccountName).toBe('旧账户（已停用）')
    expect(runtime.catalog.categories).toHaveBeenCalledWith(undefined, 'ACTIVE')
    expect(runtime.catalog.accounts).toHaveBeenCalledWith('ACTIVE')
  })

  it('hidden detail response cannot paint data into a departed page', async () => {
    const pending = deferred<typeof entry>()
    const runtime = runtimeFor(); runtime.entries.detail.mockReturnValueOnce(pending.promise)
    const page = await loadPage('entry-detail', runtime)
    page.onLoad({ id: '40' }); const operation = page.onShow(); await Promise.resolve(); page.onHide()
    pending.resolve(entry); await operation
    expect(page.data.entry).toBeNull()
  })
})
