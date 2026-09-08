import { getRuntime } from '../../runtime'
import type { Account, Category, EntryType } from '../../types/catalog'
import type { CreatorOption, Entry } from '../../types/entry'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

interface HistoryCategory extends Category { displayName: string }
interface HistoryAccount extends Account { displayName: string }
type LoadState = 'loading' | 'ready' | 'empty' | 'error'

function resourceLabel(name: string, status: string): string {
  return status === 'DISABLED' ? `${name}（已停用）` : name
}

Page({
  data: {
    items: [] as Entry[], creators: [] as CreatorOption[], categories: [] as HistoryCategory[], accounts: [] as HistoryAccount[],
    dateFrom: '', dateTo: '', entryType: '' as '' | EntryType, categoryId: 0, accountId: 0,
    categoryName: '', accountName: '',
    createdBy: 0, keyword: '', page: 1, pageSize: 20, total: 0,
    loading: false, loadState: 'loading' as LoadState, errorMessage: '', requestId: '',
  },
  _active: true, _generation: 0,
  async onShow() { this._active = true; this.setData({ loading: false }); await this.load(true) },
  onHide() { this._active = false; ++this._generation; this.setData({ loading: false }) },
  onUnload() { this._active = false; ++this._generation; this.setData({ loading: false }) },

  filters() {
    return {
      dateFrom: this.data.dateFrom || undefined, dateTo: this.data.dateTo || undefined,
      entryType: this.data.entryType || undefined, categoryId: this.data.categoryId || undefined,
      accountId: this.data.accountId || undefined, createdBy: this.data.createdBy || undefined,
      keyword: this.data.keyword || undefined, page: this.data.page, pageSize: this.data.pageSize,
    }
  },

  async load(includeOptions: boolean) {
    const generation = ++this._generation; const runtime = getRuntime()
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, loadState: 'loading', errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext(); if (!current()) return
      const requests = includeOptions ? Promise.all([
        runtime.entries.list(this.filters()), runtime.entries.creators(),
        runtime.catalog.categories(undefined, undefined), runtime.catalog.accounts(undefined),
      ]) : Promise.all([runtime.entries.list(this.filters())])
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
          creators: creators.items, categories: categoryOptions, accounts: accountOptions,
          categoryName: categoryOptions.find(item => item.id === this.data.categoryId)?.displayName || '',
          accountName: accountOptions.find(item => item.id === this.data.accountId)?.displayName || '',
          loadState: list.items.length ? 'ready' : 'empty' })
      } else this.setData({ items: list.items, page: list.page, pageSize: list.pageSize, total: list.total,
        loadState: list.items.length ? 'ready' : 'empty' })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ items: [], total: 0, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
    } finally { if (current()) this.setData({ loading: false }) }
  },

  async onApplyFilters() { this.setData({ page: 1 }); await this.load(false) },
  async onClearFilters() {
    this.setData({ dateFrom: '', dateTo: '', entryType: '', categoryId: 0, accountId: 0,
      categoryName: '', accountName: '', createdBy: 0, keyword: '', page: 1 }); await this.load(false)
  },
  async previousPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); await this.load(false) } },
  async nextPage() { if (this.data.page * this.data.pageSize < this.data.total) { this.setData({ page: this.data.page + 1 }); await this.load(false) } },
  onDateFrom(event: WechatMiniprogram.PickerChange) { this.setData({ dateFrom: String(event.detail.value) }) },
  onDateTo(event: WechatMiniprogram.PickerChange) { this.setData({ dateTo: String(event.detail.value) }) },
  onKeyword(event: WechatMiniprogram.Input) { this.setData({ keyword: event.detail.value }) },
  onType(event: WechatMiniprogram.PickerChange) { this.setData({ entryType: ['', 'EXPENSE', 'INCOME'][Number(event.detail.value)] as '' | EntryType }) },
  onCategory(event: WechatMiniprogram.PickerChange) {
    const selected = this.data.categories[Number(event.detail.value)]
    this.setData({ categoryId: selected?.id ?? 0, categoryName: selected?.displayName ?? '' })
  },
  onAccount(event: WechatMiniprogram.PickerChange) {
    const selected = this.data.accounts[Number(event.detail.value)]
    this.setData({ accountId: selected?.id ?? 0, accountName: selected?.displayName ?? '' })
  },
  onCreator(event: WechatMiniprogram.PickerChange) { this.setData({ createdBy: this.data.creators[Number(event.detail.value)]?.userId ?? 0 }) },
  openCreate() { if (!this.data.loading) wx.navigateTo({ url: '/pages/entry-create/index' }) },
  openDetail(event: WechatMiniprogram.TouchEvent) {
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.items.some(item => item.id === id)) wx.navigateTo({ url: `/pages/entry-detail/index?id=${id}` })
  },
})
