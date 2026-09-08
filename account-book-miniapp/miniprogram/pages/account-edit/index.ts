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
  },
  _active: true,
  _generation: 0,
  _dirty: false,
  _loadedId: 0,
  _loadedRevision: -1,

  onLoad(query: Record<string, string>) {
    const id = Number(query.id || 0)
    this.setData({ id: Number.isSafeInteger(id) && id > 0 ? id : 0, editing: id > 0 })
    this._dirty = false
    this._loadedId = 0
    this._loadedRevision = -1
  },

  async onShow() {
    this._active = true
    this.setData({ busy: false, loading: false })
    await this.loadAccount(false)
  },

  async loadAccount(forceReload: boolean) {
    const generation = ++this._generation
    const runtime = getRuntime()
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const preserveConflict = !forceReload && this._dirty && this.data.canReload
    this.setData(preserveConflict
      ? { loading: true }
      : { loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const role = runtime.session.getUser()?.role
      this.setData({ canManage: role === 'OWNER' || role === 'ADMIN' })
      const revision = runtime.session.getRevision()
      if (this.data.editing) {
        const sameLoadedSession = this._loadedId === this.data.id && this._loadedRevision === revision
        if (!forceReload && this._dirty && sameLoadedSession) return
        const account = await runtime.catalog.account(this.data.id)
        if (current()) {
          this.setData({ ...account, canReload: false })
          this._dirty = false
          this._loadedId = this.data.id
          this._loadedRevision = revision
        }
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
      this._loadedRevision = revision
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  onHide() {
    this._active = false
    ++this._generation
    this.setData({ busy: false, loading: false })
  },

  onUnload() {
    this._active = false
    ++this._generation
    this.setData({ busy: false, loading: false })
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this._dirty = true
    this.setData({ name: event.detail.value })
  },

  onBalanceInput(event: WechatMiniprogram.Input) {
    if (!this.data.editing) {
      this._dirty = true
      this.setData({ initialBalance: event.detail.value })
    }
  },

  onSortInput(event: WechatMiniprogram.Input) {
    this._dirty = true
    this.setData({ sortNo: Number(event.detail.value) })
  },

  onTypeChange(event: WechatMiniprogram.PickerChange) {
    if (!this.data.editing) {
      const types: AccountType[] = ['CASH', 'WECHAT', 'BANK', 'ALIPAY', 'OTHER']
      this._dirty = true
      this.setData({ accountType: types[Number(event.detail.value)] || 'OTHER' })
    }
  },

  onStatusChange(event: WechatMiniprogram.PickerChange) {
    this._dirty = true
    this.setData({ status: Number(event.detail.value) === 0 ? 'ACTIVE' : 'DISABLED' })
  },

  async onReload() {
    if (!this.data.editing || !this.data.canReload || this.data.loading || this.data.busy) return
    const runtime = getRuntime()
    const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const confirmed = await new Promise<boolean>(resolve => wx.showModal({
      title: '重新加载账户',
      content: '重新加载将丢弃当前未保存的修改，是否继续？',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    }))
    if (confirmed && current()) await this.loadAccount(true)
  },

  async onSubmit() {
    if (!this.data.canManage || this.data.busy || this.data.loading) return
    const runtime = getRuntime()
    const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const name = this.data.name.trim()
    this.setData({ busy: true, errorMessage: '', requestId: '' })
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
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({
        errorMessage: view.message,
        requestId: view.requestId,
        canReload: this.data.editing && isResourceStateChanged(error),
      })
    } finally {
      if (current()) this.setData({ busy: false })
    }
  },
})
