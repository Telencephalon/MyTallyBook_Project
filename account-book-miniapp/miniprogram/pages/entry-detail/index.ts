import { getRuntime } from '../../runtime'
import type { Entry } from '../../types/entry'
import { validEntryId } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'
import { navigateToPage } from '../../utils/navigation'
import { creatorScopeIdentity } from '../../utils/creator-scope'

Page({
  data: { id: 0, entry: null as Entry | null, canEdit: false, canDelete: false,
    loading: false, busy: false, canRetry: false, errorMessage: '', requestId: '' },
  _active: true, _generation: 0, _writeOperation: 0,
  _scopeIdentity: '',
  syncScope() {
    const identity = creatorScopeIdentity(getRuntime().session)
    if (identity !== this._scopeIdentity) {
      this._scopeIdentity = identity
      this.setData({ entry: null, canEdit: false, canDelete: false, loading: false, canRetry: false,
        errorMessage: '', requestId: '' })
    }
  },
  onLoad(query: Record<string, string>) {
    try { this.setData({ id: validEntryId(query.id) }) }
    catch (error) { const view = toErrorView(error); this.setData({ errorMessage: view.message }) }
  },
  async onShow() {
    this._active = true; this.setData({ loading: false })
    if (this.data.busy) return
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
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.syncScope()
    let identity = ''
    const current = () => {
      if (!this._active || generation !== this._generation) return false
      if (identity && identity !== creatorScopeIdentity(runtime.session)) { this.syncScope(); return false }
      if (!guard()) { this.syncScope(); return false }
      return true
    }
    this.setData({ loading: true, errorMessage: '', requestId: '', canRetry: false })
    try {
      await runtime.flow.refreshContext(); if (!current()) return
      this.syncScope()
      identity = this._scopeIdentity
      this.setData({ loading: true })
      const entry = await runtime.entries.detail(this.data.id)
      if (current()) this.setData({ entry, canEdit: entry.canEdit, canDelete: entry.canDelete, canRetry: false })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      const missing = typeof error === 'object' && error !== null && (error as { code?: unknown }).code === 'RESOURCE_NOT_FOUND'
      this.setData({ entry: missing ? null : this.data.entry, canEdit: false, canDelete: false,
        canRetry: !missing, errorMessage: missing ? '账单不存在或已删除' : view.message, requestId: view.requestId })
    } finally { if (current()) this.setData({ loading: false }) }
  },
  onHide() { this._active = false; ++this._generation; this.setData({ loading: false }) },
  onUnload() { this._active = false; ++this._generation; this.setData({ loading: false }) },
  openEdit() { this.syncScope(); if (this.data.canEdit && !this.data.busy) wx.navigateTo({ url: `/pages/entry-edit/index?id=${this.data.id}` }) },
  async onRetry() { if (!this.data.canRetry || this.data.loading || this.data.busy) return; await this.onShow() },
  async onDelete() {
    this.syncScope()
    const item = this.data.entry
    if (!item || !this.data.canDelete || this.data.busy) return
    const runtime = getRuntime(); const generation = this._generation; const operation = ++this._writeOperation
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const identity = this._scopeIdentity
    const current = () => {
      if (!this._active) return false
      if (identity !== creatorScopeIdentity(runtime.session)) { this.syncScope(); return false }
      return pageCurrent()
    }
    this.setData({ busy: true })
    try {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '删除账单', content: '删除后该账单不再计入余额和统计，是否继续？',
        success: result => resolve(result.confirm), fail: () => resolve(false),
      }))
      if (!confirmed || !current()) return
      await runtime.entries.remove(item.id, item.version)
      if (current()) navigateToPage('/pages/entry-list/index')
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId, canRetry: false })
    } finally {
      if (operation === this._writeOperation) this.setData({ busy: false })
    }
  },
})
