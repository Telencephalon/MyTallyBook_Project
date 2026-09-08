import { getRuntime } from '../../runtime'
import type { Entry } from '../../types/entry'
import { validEntryId } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

Page({
  data: { id: 0, entry: null as Entry | null, canEdit: false, canDelete: false,
    loading: false, busy: false, errorMessage: '', requestId: '' },
  _active: true, _generation: 0, _writeGeneration: 0,
  onLoad(query: Record<string, string>) {
    try { this.setData({ id: validEntryId(query.id) }) }
    catch (error) { const view = toErrorView(error); this.setData({ errorMessage: view.message }) }
  },
  async onShow() {
    this._active = true; this.setData({ loading: false, busy: false })
    if (!this.data.id) return
    const generation = ++this._generation; const runtime = getRuntime()
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext(); if (!current()) return
      const entry = await runtime.entries.detail(this.data.id)
      if (current()) this.setData({ entry, canEdit: entry.canEdit, canDelete: entry.canDelete })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      const missing = typeof error === 'object' && error !== null && (error as { code?: unknown }).code === 'RESOURCE_NOT_FOUND'
      this.setData({ entry: missing ? null : this.data.entry, canEdit: false, canDelete: false,
        errorMessage: missing ? '账单不存在或已删除' : view.message, requestId: view.requestId })
    } finally { if (current()) this.setData({ loading: false }) }
  },
  onHide() { this._active = false; ++this._generation; ++this._writeGeneration; this.setData({ loading: false, busy: false }) },
  onUnload() { this._active = false; ++this._generation; ++this._writeGeneration; this.setData({ loading: false, busy: false }) },
  openEdit() { if (this.data.canEdit && !this.data.busy) wx.navigateTo({ url: `/pages/entry-edit/index?id=${this.data.id}` }) },
  async onDelete() {
    const item = this.data.entry
    if (!item || !this.data.canDelete || this.data.busy) return
    const runtime = getRuntime(); const generation = this._generation; const write = ++this._writeGeneration
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => pageCurrent() && write === this._writeGeneration
    this.setData({ busy: true })
    try {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '删除账单', content: '删除后该账单不再计入余额和统计，是否继续？',
        success: result => resolve(result.confirm), fail: () => resolve(false),
      }))
      if (!confirmed || !current()) return
      await runtime.entries.remove(item.id, item.version)
      if (current()) wx.redirectTo({ url: '/pages/entry-list/index' })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId })
    } finally { if (current()) this.setData({ busy: false }) }
  },
})
