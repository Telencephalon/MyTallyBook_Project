import { getRuntime } from '../../runtime'
import type { EntryType, ResourceStatus } from '../../types/catalog'
import type { Entry } from '../../types/entry'
import { entryDraft, validEntryId } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

interface Choice { id: number; name: string; status: ResourceStatus; original?: boolean }

function conflict(error: unknown): boolean {
  return typeof error === 'object' && error !== null
    && (error as { code?: unknown }).code === 'ENTRY_VERSION_CONFLICT'
}

Page({
  data: {
    id: 0, entryType: 'EXPENSE' as EntryType, amount: '', categoryId: 0, accountId: 0,
    selectedCategoryName: '', selectedAccountName: '',
    entryDate: '', note: '', version: 0, categoryOptions: [] as Choice[], accountOptions: [] as Choice[],
    loading: false, busy: false, canEdit: false, canReload: false, canRetryRead: false, errorMessage: '', requestId: '',
  },
  _active: true, _generation: 0, _writeOperation: 0, _dirty: false, _loadedId: 0, _loadedRevision: -1,
  onLoad(query: Record<string, string>) {
    try { this.setData({ id: validEntryId(query.id) }) }
    catch (error) { const view = toErrorView(error); this.setData({ errorMessage: view.message }) }
  },
  async onShow() { this._active = true; this.setData({ loading: false }); if (!this.data.busy) await this.loadEntry(false) },
  onHide() { this._active = false; ++this._generation; this.setData({ loading: false }) },
  onUnload() { this._active = false; ++this._generation; this.setData({ loading: false }) },

  async loadEntry(force: boolean) {
    if (!this.data.id) return
    const generation = ++this._generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, errorMessage: view.message, requestId: view.requestId })
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const preserveConflict = !force && this._dirty && this.data.canReload
    this.setData(preserveConflict
      ? { loading: true }
      : { loading: true, errorMessage: '', requestId: '', canRetryRead: false })
    try {
      await runtime.flow.refreshContext(); if (!current()) return
      const revision = runtime.session.getRevision()
      if (!force && this._dirty && this._loadedId === this.data.id && this._loadedRevision === revision) return
      const entry = await runtime.entries.detail(this.data.id); if (!current()) return
      const [categories, accounts] = await Promise.all([
        runtime.catalog.categories(entry.entryType, 'ACTIVE'), runtime.catalog.accounts('ACTIVE'),
      ])
      if (!current()) return
      this.applyLoaded(entry,
        categories.items.map(item => ({ id: item.id, name: item.name, status: item.status })),
        accounts.items.map(item => ({ id: item.id, name: item.name, status: item.status })))
      this._dirty = false; this._loadedId = entry.id; this._loadedRevision = revision
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      const missing = typeof error === 'object' && error !== null && (error as { code?: unknown }).code === 'RESOURCE_NOT_FOUND'
      this.setData({ errorMessage: missing ? '账单不存在或已删除' : view.message, requestId: view.requestId, canRetryRead: !missing })
    } finally { if (current()) this.setData({ loading: false }) }
  },

  applyLoaded(entry: Entry, categories: Choice[], accounts: Choice[]) {
    if (!categories.some(item => item.id === entry.categoryId)) {
      categories.push({ id: entry.categoryId, name: `${entry.categoryName}（已停用）`, status: entry.categoryStatus, original: true })
    }
    if (!accounts.some(item => item.id === entry.accountId)) {
      accounts.push({ id: entry.accountId, name: `${entry.accountName}（已停用）`, status: entry.accountStatus, original: true })
    }
    this.setData({ entryType: entry.entryType, amount: entry.amount, categoryId: entry.categoryId,
      accountId: entry.accountId, entryDate: entry.entryDate, note: entry.note || '', version: entry.version,
      selectedCategoryName: categories.find(item => item.id === entry.categoryId)?.name || entry.categoryName,
      selectedAccountName: accounts.find(item => item.id === entry.accountId)?.name || entry.accountName,
      categoryOptions: categories, accountOptions: accounts, canEdit: entry.canEdit, canReload: false, canRetryRead: false })
  },

  formLocked() {
    return !this.data.canEdit || this.data.loading || this.data.busy
      || this.data.canReload || this.data.canRetryRead
  },
  onAmountInput(event: WechatMiniprogram.Input) {
    if (!this.formLocked()) { this._dirty = true; this.setData({ amount: event.detail.value }) }
  },
  onNoteInput(event: WechatMiniprogram.Input) {
    if (!this.formLocked()) { this._dirty = true; this.setData({ note: event.detail.value }) }
  },
  onDateChange(event: WechatMiniprogram.PickerChange) {
    if (!this.formLocked()) { this._dirty = true; this.setData({ entryDate: String(event.detail.value) }) }
  },
  onCategoryChange(event: WechatMiniprogram.PickerChange) {
    if (!this.formLocked()) {
      const selected = this.data.categoryOptions[Number(event.detail.value)]
      this._dirty = true
      this.setData({ categoryId: selected?.id ?? 0, selectedCategoryName: selected?.name ?? '' })
    }
  },
  onAccountChange(event: WechatMiniprogram.PickerChange) {
    if (!this.formLocked()) {
      const selected = this.data.accountOptions[Number(event.detail.value)]
      this._dirty = true
      this.setData({ accountId: selected?.id ?? 0, selectedAccountName: selected?.name ?? '' })
    }
  },
  async onTypeChange(event: WechatMiniprogram.PickerChange) {
    if (this.formLocked()) return
    const entryType: EntryType = Number(event.detail.value) === 0 ? 'EXPENSE' : 'INCOME'
    const runtime = getRuntime(); const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this._dirty = true; this.setData({ entryType, categoryId: 0, selectedCategoryName: '' })
    try {
      const result = await runtime.catalog.categories(entryType, 'ACTIVE')
      if (current() && this.data.entryType === entryType) this.setData({ categoryOptions: result.items.map(item => ({ id: item.id, name: item.name, status: item.status })) })
    } catch (error) {
      if (current() && this.data.entryType === entryType) { const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId }) }
    }
  },
  async onReload() {
    if (!this.data.canReload || this.data.loading || this.data.busy) return
    const runtime = getRuntime(); const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const confirmed = await new Promise<boolean>(resolve => wx.showModal({
      title: '重新加载账单', content: '重新加载将丢弃当前未保存修改，是否继续？',
      success: result => resolve(result.confirm), fail: () => resolve(false),
    }))
    if (confirmed && current()) await this.loadEntry(true)
  },
  async retry() {
    if (!this.data.canRetryRead || this.data.loading || this.data.busy) return
    await this.loadEntry(false)
  },
  async onSubmit() {
    if (this.formLocked() || !this.data.id) return
    let body
    try { body = { ...entryDraft(this.data), version: this.data.version } }
    catch (error) { const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId }); return }
    const runtime = getRuntime(); const generation = this._generation
    const operation = ++this._writeOperation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ busy: true, errorMessage: '', requestId: '', canRetryRead: false })
    try {
      const saved = await runtime.entries.update(this.data.id, body)
      if (current()) wx.redirectTo({ url: `/pages/entry-detail/index?id=${saved.id}` })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId, canReload: conflict(error), canRetryRead: false })
    } finally {
      if (operation === this._writeOperation) this.setData({ busy: false })
    }
  },
})
