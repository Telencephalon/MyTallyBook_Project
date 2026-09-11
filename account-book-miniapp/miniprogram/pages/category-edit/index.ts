import { getRuntime } from '../../runtime'
import type { EntryType, ResourceStatus } from '../../types/catalog'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

function isResourceStateChanged(error: unknown) {
  return typeof error === 'object'
    && error !== null
    && (error as { code?: unknown }).code === 'RESOURCE_STATE_CHANGED'
}

Page({
  data: {
    id: 0,
    name: '',
    entryType: 'EXPENSE' as EntryType,
    icon: '',
    color: '',
    sortNo: 0,
    status: 'ACTIVE' as ResourceStatus,
    editing: false,
    loading: false,
    busy: false,
    canManage: false,
    errorMessage: '',
    requestId: '',
    canReload: false,
    canRetryRead: false,
  },
  _active: true,
  _generation: 0,
  _writeOperation: 0,
  _dirty: false,
  _loadedId: 0,
  _loadedRevision: -1,
  _contextKey: '',
  _draftOwner: '',
  _resourceReadFailed: false,

  onLoad(query: Record<string, string>) {
    const id = Number(query.id || 0)
    this.setData({
      id: Number.isSafeInteger(id) && id > 0 ? id : 0,
      editing: id > 0,
      name: '',
      entryType: 'EXPENSE',
      icon: '',
      color: '',
      sortNo: 0,
      status: 'ACTIVE',
      canManage: false,
      errorMessage: '',
      requestId: '',
      canReload: false,
      canRetryRead: false,
      loading: false,
    })
    this._dirty = false
    this._loadedId = 0
    this._loadedRevision = -1
    this._contextKey = ''
    this._draftOwner = ''
    this._resourceReadFailed = false
  },

  sessionKey(runtime: ReturnType<typeof getRuntime>) {
    return `${this.data.id}:${runtime.session.getRevision()}:${runtime.session.getToken() || ''}`
  },

  clearForContextChange() {
    this.setData({
      name: '',
      entryType: 'EXPENSE',
      icon: '',
      color: '',
      sortNo: 0,
      status: 'ACTIVE',
      canManage: false,
      errorMessage: '',
      requestId: '',
      canReload: false,
      canRetryRead: false,
      loading: false,
    })
    this._dirty = false
    this._loadedId = 0
    this._loadedRevision = -1
    this._draftOwner = ''
    this._resourceReadFailed = false
  },

  syncContext(runtime: ReturnType<typeof getRuntime>) {
    const key = this.sessionKey(runtime)
    if (this._contextKey && this._contextKey !== key) this.clearForContextChange()
    this._contextKey = key
    return key
  },

  markDraftDirty() {
    if (this.data.busy || this.data.loading) return false
    try {
      const runtime = getRuntime()
      this._draftOwner = this.syncContext(runtime)
    } catch {
      // Keep the existing local edit behavior if runtime is unavailable.
    }
    this._dirty = true
    return true
  },

  async onShow() {
    this._active = true
    this.setData({ loading: false })
    try { this.syncContext(getRuntime()) } catch { /* loadCategory reports unavailable runtime */ }
    if (!this.data.busy) await this.loadCategory(false)
  },

  async loadCategory(forceReload: boolean) {
    const generation = ++this._generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, errorMessage: view.message, requestId: view.requestId })
      return
    }
    const contextKey = this.syncContext(runtime)
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => guard() && this.sessionKey(runtime) === contextKey
    const preserveConflict = !forceReload && this._dirty && this.data.canReload
    this.setData(preserveConflict
      ? { loading: true }
      : { loading: true, errorMessage: '', requestId: '', canRetryRead: false })
    let resourceReadAttempted = false
    try {
      await runtime.flow.refreshContext()
      if (!current()) {
        this.syncContext(runtime)
        return
      }
      const role = runtime.session.getUser()?.role
      this.setData({ canManage: role === 'OWNER' || role === 'ADMIN' })
      const revision = runtime.session.getRevision()
      if (this.data.editing) {
        const sameLoadedSession = this._loadedId === this.data.id && this._loadedRevision === revision
        if (!forceReload && this._dirty && sameLoadedSession && !this._resourceReadFailed) return
        resourceReadAttempted = true
        const category = await runtime.catalog.category(this.data.id)
        if (!current()) {
          this.syncContext(runtime)
          return
        }
        const preserveDraft = !forceReload && this._dirty && this._draftOwner === contextKey
        this.setData({
          ...category,
          ...(preserveDraft ? {
            name: this.data.name,
            icon: this.data.icon,
            color: this.data.color,
            sortNo: this.data.sortNo,
            status: this.data.status,
          } : {
            icon: category.icon || '',
            color: category.color || '',
          }),
          canReload: false, canRetryRead: false,
        })
        this._dirty = preserveDraft
        this._draftOwner = contextKey
        this._loadedId = this.data.id
        this._loadedRevision = revision
        this._resourceReadFailed = false
      } else if (this._loadedRevision !== -1 && this._loadedRevision !== revision) {
        this.setData({
          name: '',
          entryType: 'EXPENSE',
          icon: '',
          color: '',
          sortNo: 0,
          status: 'ACTIVE',
          canReload: false,
        })
        this._dirty = false
      }
      if (current()) this._loadedRevision = revision
    } catch (error) {
      if (!current()) {
        this.syncContext(runtime)
        return
      }
      const view = toErrorView(error)
      if (resourceReadAttempted) this._resourceReadFailed = true
      this.setData({ errorMessage: view.message, requestId: view.requestId, canRetryRead: true })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  onHide() {
    this._active = false
    ++this._generation
    this.setData({ loading: false })
  },

  onUnload() {
    this._active = false
    ++this._generation
    this.setData({ loading: false })
  },

  onNameInput(event: WechatMiniprogram.Input) {
    if (!this.markDraftDirty()) return
    this.setData({ name: event.detail.value })
  },

  onIconInput(event: WechatMiniprogram.Input) {
    if (!this.markDraftDirty()) return
    this.setData({ icon: event.detail.value })
  },

  onColorInput(event: WechatMiniprogram.Input) {
    if (!this.markDraftDirty()) return
    this.setData({ color: event.detail.value })
  },

  onSortInput(event: WechatMiniprogram.Input) {
    if (!this.markDraftDirty()) return
    this.setData({ sortNo: Number(event.detail.value) })
  },

  onTypeChange(event: WechatMiniprogram.PickerChange) {
    if (!this.data.editing) {
      if (!this.markDraftDirty()) return
      this.setData({ entryType: Number(event.detail.value) === 0 ? 'EXPENSE' : 'INCOME' })
    }
  },

  onStatusChange(event: WechatMiniprogram.PickerChange) {
    if (!this.markDraftDirty()) return
    this.setData({ status: Number(event.detail.value) === 0 ? 'ACTIVE' : 'DISABLED' })
  },

  async onReload() {
    if (!this.data.editing || !this.data.canReload || this.data.loading || this.data.busy) return
    const runtime = getRuntime()
    const contextKey = this.sessionKey(runtime)
    if (this._contextKey !== contextKey || (this._draftOwner && this._draftOwner !== contextKey)) {
      this.clearForContextChange()
      this._contextKey = contextKey
      return
    }
    const generation = this._generation
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => guard() && this.sessionKey(runtime) === contextKey
    const confirmed = await new Promise<boolean>(resolve => wx.showModal({
      title: '重新加载分类',
      content: '重新加载将丢弃当前未保存的修改，是否继续？',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    }))
    if (confirmed && current()) await this.loadCategory(true)
  },

  async retry() {
    if (!this.data.canRetryRead || this.data.loading || this.data.busy) return
    await this.loadCategory(false)
  },

  async onSubmit() {
    if (!this.data.canManage || this.data.loading || this.data.busy || this.data.canRetryRead) return
    const runtime = getRuntime()
    const contextKey = this.sessionKey(runtime)
    if (this._contextKey !== contextKey || (this._draftOwner && this._draftOwner !== contextKey)) {
      this.clearForContextChange()
      this._contextKey = contextKey
      return
    }
    const generation = this._generation
    const operation = ++this._writeOperation
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => guard() && this.sessionKey(runtime) === contextKey
    const body = {
      name: this.data.name.trim(),
      icon: this.data.icon.trim() || null,
      color: this.data.color.trim() || null,
      sortNo: this.data.sortNo,
      status: this.data.status,
    }
    this.setData({ busy: true, errorMessage: '', requestId: '', canRetryRead: false })
    try {
      if (this.data.editing) await runtime.catalog.updateCategory(this.data.id, body)
      else await runtime.catalog.createCategory({ entryType: this.data.entryType, ...body })
      if (current()) wx.redirectTo({ url: '/pages/category-list/index' })
      else this.syncContext(runtime)
    } catch (error) {
      if (!current()) {
        this.syncContext(runtime)
        return
      }
      const view = toErrorView(error)
      this.setData({
        errorMessage: view.message,
        requestId: view.requestId,
        canReload: this.data.editing && isResourceStateChanged(error),
      })
    } finally {
      if (operation === this._writeOperation) this.setData({ busy: false })
    }
  },
})
