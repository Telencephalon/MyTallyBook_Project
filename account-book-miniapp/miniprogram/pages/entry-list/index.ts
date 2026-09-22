import { getRuntime } from '../../runtime'
import { APP_INFO } from '../../config/app-info'
import type { Account, Category, EntryType } from '../../types/catalog'
import type { CreatorOption, Entry } from '../../types/entry'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'
import { creatorOptions, creatorScopeIdentity } from '../../utils/creator-scope'
import { canReuseTabSnapshot, captureTabSnapshot, matchesTabSnapshot, type TabSnapshot } from '../../utils/tab-snapshot'

interface HistoryCategory extends Category { displayName: string }
interface HistoryAccount extends Account { displayName: string }
type LoadState = 'loading' | 'ready' | 'empty' | 'error'

function resourceLabel(name: string, status: string): string {
  return status === 'DISABLED' ? `${name}（已停用）` : name
}

Page({
  onShareAppMessage() {
    return { title: APP_INFO.appName, path: '/pages/login/index' }
  },

  data: {
    items: [] as Entry[], creators: [] as CreatorOption[], categories: [] as HistoryCategory[], accounts: [] as HistoryAccount[],
    dateFrom: '', dateTo: '', entryType: '' as '' | EntryType, categoryId: 0, accountId: 0,
    categoryName: '', accountName: '',
    quickCategories: [] as Array<{ id: number; displayName: string }>, categoryIndex: 0, creatorIndex: 0,
    dateFilterExpanded: false, dateDraftFrom: '', dateDraftTo: '', dateFilterError: '',
    createdBy: 0, creatorName: '全部成员', canSelectCreator: false, keyword: '', page: 1, pageSize: 20, total: 0,
    loading: false, loadState: 'loading' as LoadState, errorMessage: '', requestId: '', filtersExpanded: false,
  },
  _active: true, _generation: 0,
  _creatorIdentity: '',
  _snapshot: null as TabSnapshot | null,
  _displaySnapshot: null as TabSnapshot | null,
  async onShow() {
    this.getTabBar?.()?.setData({ selected: 1 })
    this._active = true
    this.setData({ loading: false })
    await this.load(true, true)
  },
  onHide() { this._active = false; ++this._generation; this.setData({ loading: false, dateFilterExpanded: false }) },
  onUnload() { this._active = false; ++this._generation; this.setData({ loading: false }) },
  toggleFilters() { this.setData({ filtersExpanded: !this.data.filtersExpanded, dateFilterExpanded: false }) },
  toggleDateFilter() {
    if (!this._active || this.data.loading) return
    this.setData({ dateFilterExpanded: !this.data.dateFilterExpanded, filtersExpanded: false,
      dateDraftFrom: this.data.dateFrom, dateDraftTo: this.data.dateTo, dateFilterError: '' })
  },
  onDateDraftFrom(event: WechatMiniprogram.PickerChange) {
    this.setData({ dateDraftFrom: String(event.detail.value), dateFilterError: '' })
  },
  onDateDraftTo(event: WechatMiniprogram.PickerChange) {
    this.setData({ dateDraftTo: String(event.detail.value), dateFilterError: '' })
  },
  async applyDateFilter() {
    if (!this._active || this.data.loading || !this.data.dateFilterExpanded) return
    const { dateDraftFrom, dateDraftTo } = this.data
    if (dateDraftFrom && dateDraftTo && dateDraftFrom > dateDraftTo) {
      this.setData({ dateFilterError: '开始日期不能晚于结束日期' }); return
    }
    this.setData({ dateFrom: dateDraftFrom, dateTo: dateDraftTo, dateFilterExpanded: false, page: 1 })
    await this.load(false)
  },
  async clearDateFilter() {
    if (!this._active || this.data.loading) return
    this.setData({ dateFrom: '', dateTo: '', dateDraftFrom: '', dateDraftTo: '',
      dateFilterExpanded: false, dateFilterError: '', page: 1 })
    await this.load(false)
  },

  filters() {
    return {
      dateFrom: this.data.dateFrom || undefined, dateTo: this.data.dateTo || undefined,
      entryType: this.data.entryType || undefined, categoryId: this.data.categoryId || undefined,
      accountId: this.data.accountId || undefined,
      createdBy: getRuntime().session.getUser()?.role === 'OWNER' ? this.data.createdBy || undefined : undefined,
      keyword: this.data.keyword || undefined, page: this.data.page, pageSize: this.data.pageSize,
    }
  },

  async load(includeOptions: boolean, reuseSnapshot = false) {
    const generation = ++this._generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      return
    }
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.syncCreatorScope()
    let identity = ''
    const current = () => {
      if (!this._active) return false
      if (identity && identity !== creatorScopeIdentity(runtime.session)) { this.syncCreatorScope(); return false }
      if (!guard()) { this.syncCreatorScope(); return false }
      return true
    }
    const preserveDisplay = reuseSnapshot && matchesTabSnapshot(this._displaySnapshot, runtime.session, JSON.stringify(this.filters()))
    if (!reuseSnapshot) this._snapshot = null
    if (!preserveDisplay) this._displaySnapshot = null
    this.setData({ loading: true, errorMessage: '', requestId: '',
      ...(!preserveDisplay ? { items: [], total: 0, loadState: 'loading' as LoadState } : {}) })
    try {
      if (reuseSnapshot) await runtime.flow.refreshContext({ reusePending: true })
      else await runtime.flow.refreshContext()
      if (!current()) return
      this.syncCreatorScope()
      identity = this._creatorIdentity
      const filters = this.filters()
      const query = JSON.stringify(filters)
      if (reuseSnapshot && canReuseTabSnapshot(this._snapshot, runtime.session, query)) return
      const snapshot = captureTabSnapshot(runtime.session, query)
      this._snapshot = null
      this.setData({ loading: true,
        ...(!matchesTabSnapshot(this._displaySnapshot, runtime.session, query) ? { loadState: 'loading' as LoadState } : {}) })
      const requests = includeOptions ? Promise.all([
        runtime.entries.list(filters),
        this.data.canSelectCreator ? runtime.entries.creators() : Promise.resolve({ items: [] }),
        runtime.catalog.categories(undefined, undefined), runtime.catalog.accounts(undefined),
      ]) : Promise.all([runtime.entries.list(filters)])
      const results = await requests; if (!current()) return
      const list = results[0]
      if (includeOptions) {
        const creators = results[1] as Awaited<ReturnType<typeof runtime.entries.creators>>
        const categories = results[2] as Awaited<ReturnType<typeof runtime.catalog.categories>>
        const accounts = results[3] as Awaited<ReturnType<typeof runtime.catalog.accounts>>
        const categoryOptions = categories.items.map(item => ({
          ...item, displayName: resourceLabel(item.name, item.status),
        }))
        const accountOptions = accounts.items.map(item => ({
          ...item, displayName: resourceLabel(item.name, item.status),
        }))
        this.setData({ items: list.items, page: list.page, pageSize: list.pageSize, total: list.total,
          creators: this.data.canSelectCreator ? creatorOptions(creators.items) : [], categories: categoryOptions, accounts: accountOptions,
          quickCategories: [{ id: 0, displayName: '全部分类' }, ...categoryOptions],
          categoryIndex: categoryOptions.findIndex(item => item.id === this.data.categoryId) + 1,
          creatorIndex: creators.items.findIndex(item => item.userId === this.data.createdBy) + 1,
          creatorName: creators.items.find(item => item.userId === this.data.createdBy)?.displayName || '全部成员',
          categoryName: categoryOptions.find(item => item.id === this.data.categoryId)?.displayName || '',
          accountName: accountOptions.find(item => item.id === this.data.accountId)?.displayName || '',
          loadState: list.items.length ? 'ready' : 'empty' })
      } else this.setData({ items: list.items, page: list.page, pageSize: list.pageSize, total: list.total,
        loadState: list.items.length ? 'ready' : 'empty' })
      this._displaySnapshot = snapshot
      // Filter and pagination reads do not refresh the catalog/creator options.
      if (includeOptions) this._snapshot = snapshot
    } catch (error) {
      if (!current()) return
      this._snapshot = null
      this._displaySnapshot = null
      const view = toErrorView(error)
      this.setData({ items: [], total: 0, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
    } finally { if (current()) this.setData({ loading: false }) }
  },

  async onApplyFilters() { this.setData({ page: 1 }); await this.load(false) },
  syncCreatorScope() {
    const session = getRuntime().session
    const identity = creatorScopeIdentity(session)
    const changed = this._creatorIdentity !== identity
    this._creatorIdentity = identity
    if (changed) { this._snapshot = null; this._displaySnapshot = null }
    this.setData({ canSelectCreator: session.getUser()?.role === 'OWNER',
      ...(changed ? { createdBy: 0, creatorName: '全部成员', creatorIndex: 0, creators: [], items: [], total: 0, page: 1, loading: false,
        dateFilterExpanded: false, dateDraftFrom: '', dateDraftTo: '', dateFilterError: '',
        loadState: 'empty' as LoadState, errorMessage: '', requestId: '' } : {}) })
  },
  async retry() { if (!this.data.loading) await this.load(true) },
  async onClearFilters() {
    this.setData({ dateFrom: '', dateTo: '', entryType: '', categoryId: 0, accountId: 0,
      categoryName: '', accountName: '', categoryIndex: 0, createdBy: 0, creatorName: '全部成员', creatorIndex: 0,
      dateFilterExpanded: false, dateFilterError: '', keyword: '', page: 1 }); await this.load(false)
  },
  async previousPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); await this.load(false) } },
  async nextPage() { if (this.data.page * this.data.pageSize < this.data.total) { this.setData({ page: this.data.page + 1 }); await this.load(false) } },
  onDateFrom(event: WechatMiniprogram.PickerChange) { this.setData({ dateFrom: String(event.detail.value) }) },
  onDateTo(event: WechatMiniprogram.PickerChange) { this.setData({ dateTo: String(event.detail.value) }) },
  onKeyword(event: WechatMiniprogram.Input) { this.setData({ keyword: event.detail.value }) },
  onType(event: WechatMiniprogram.PickerChange) { this.setData({ entryType: ['', 'EXPENSE', 'INCOME'][Number(event.detail.value)] as '' | EntryType }) },
  async onQuickType(event: WechatMiniprogram.PickerChange) {
    if (!this._active || this.data.loading) return
    const entryType = (['', 'EXPENSE', 'INCOME'] as const)[Number(event.detail.value)]
    if (entryType === undefined || entryType === this.data.entryType) return
    this.setData({ entryType, page: 1, dateFilterExpanded: false })
    await this.load(false)
  },
  async onQuickCategory(event: WechatMiniprogram.PickerChange) {
    if (!this._active || this.data.loading) return
    const index = Number(event.detail.value)
    const selected = this.data.quickCategories[index]
    if (!selected || selected.id === this.data.categoryId) return
    this.setData({ categoryId: selected.id, categoryName: selected.id ? selected.displayName : '',
      categoryIndex: index, page: 1, dateFilterExpanded: false })
    await this.load(false)
  },
  onCategory(event: WechatMiniprogram.PickerChange) {
    const selected = this.data.categories[Number(event.detail.value)]
    this.setData({ categoryId: selected?.id ?? 0, categoryName: selected?.displayName ?? '',
      categoryIndex: selected ? Number(event.detail.value) + 1 : 0 })
  },
  onAccount(event: WechatMiniprogram.PickerChange) {
    const selected = this.data.accounts[Number(event.detail.value)]
    this.setData({ accountId: selected?.id ?? 0, accountName: selected?.displayName ?? '' })
  },
  async onCreator(event: WechatMiniprogram.PickerChange) {
    this.syncCreatorScope()
    if (!this._active || this.data.loading || !this.data.canSelectCreator) return
    const selected = this.data.creators[Number(event.detail.value)]
    if (!selected) return
    this.setData({ createdBy: selected.userId, creatorName: selected.displayName,
      creatorIndex: Number(event.detail.value), page: 1, dateFilterExpanded: false })
    await this.load(false)
  },
  openCreate() { if (!this.data.loading) wx.navigateTo({ url: '/pages/entry-create/index' }) },
  openDetail(event: WechatMiniprogram.TouchEvent) {
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.items.some(item => item.id === id)) wx.navigateTo({ url: `/pages/entry-detail/index?id=${id}` })
  },
})
