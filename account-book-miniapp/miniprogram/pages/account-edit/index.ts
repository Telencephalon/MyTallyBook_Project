import { getRuntime } from '../../runtime'
import type { AccountType, ResourceStatus } from '../../types/catalog'
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
    accountType: 'CASH' as AccountType,
    initialBalance: '0.00',
    currentBalance: '0.00',
    sortNo: 0,
    status: 'ACTIVE' as ResourceStatus,
    version: 0,
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
      accountType: 'CASH',
      initialBalance: '0.00',
      currentBalance: '0.00',
      sortNo: 0,
      status: 'ACTIVE',
      version: 0,
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
      accountType: 'CASH',
      initialBalance: '0.00',
      currentBalance: '0.00',
      sortNo: 0,
      status: 'ACTIVE',
      version: 0,
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
    try { this.syncContext(getRuntime()) } catch { /* loadAccount reports unavailable runtime */ }
    if (!this.data.busy) await this.loadAccount(false)
  },

  async loadAccount(forceReload: boolean) {
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
        const account = await runtime.catalog.account(this.data.id)
        if (!current()) {
          this.syncContext(runtime)
          return
        }
        const preserveDraft = !forceReload && this._dirty && this._draftOwner === contextKey
        this.setData({
          ...account,
          ...(preserveDraft ? {
            name: this.data.name,
            sortNo: this.data.sortNo,
            status: this.data.status,
          } : {}),
          canReload: false,
          canRetryRead: false,
        })
        this._dirty = preserveDraft
        this._draftOwner = contextKey
        this._loadedId = this.data.id
        this._loadedRevision = revision
        this._resourceReadFailed = false
      } else if (this._loadedRevision !== -1 && this._loadedRevision !== revision) {
        this.setData({
          name: '',
          accountType: 'CASH',
          initialBalance: '0.00',
          currentBalance: '0.00',
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

  onBalanceInput(event: WechatMiniprogram.Input) {
    if (!this.data.editing) {
      if (!this.markDraftDirty()) return
      this.setData({ initialBalance: event.detail.value })
    }
  },

  onSortInput(event: WechatMiniprogram.Input) {
    if (!this.markDraftDirty()) return
    this.setData({ sortNo: Number(event.detail.value) })
  },

  onTypeChange(event: WechatMiniprogram.PickerChange) {
    if (!this.data.editing) {
      const types: AccountType[] = ['CASH', 'WECHAT', 'BANK', 'ALIPAY', 'OTHER']
      if (!this.markDraftDirty()) return
      this.setData({ accountType: types[Number(event.detail.value)] || 'OTHER' })
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
      title: '重新加载账户',
      content: '重新加载将丢弃当前未保存的修改，是否继续？',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    }))
    if (confirmed && current()) await this.loadAccount(true)
  },

  async retry() {
    if (!this.data.canRetryRead || this.data.loading || this.data.busy) return
    await this.loadAccount(false)
  },

  async onSubmit() {
    if (!this.data.canManage || this.data.busy || this.data.loading || this.data.canRetryRead) return
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
    const name = this.data.name.trim()
    this.setData({ busy: true, errorMessage: '', requestId: '', canRetryRead: false })
    try {
      if (this.data.editing) {
        await runtime.catalog.updateAccount(this.data.id, {
          name,
          sortNo: this.data.sortNo,
          status: this.data.status,
          version: this.data.version,
        })
      } else {
        await runtime.catalog.createAccount({
          name,
          accountType: this.data.accountType,
          initialBalance: this.data.initialBalance,
          sortNo: this.data.sortNo,
          status: this.data.status,
        })
      }
      if (current()) wx.redirectTo({ url: '/pages/account-list/index' })
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
