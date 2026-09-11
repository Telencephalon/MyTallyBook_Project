import { getRuntime } from '../../runtime'
import type { Account, ResourceStatus } from '../../types/catalog'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

Page({
  data: {
    items: [] as Account[],
    status: '' as '' | ResourceStatus,
    loading: false,
    busy: false,
    canManage: false,
    errorMessage: '',
    requestId: '',
  },
  _active: true,
  _generation: 0,
  _writeOperation: 0,

  async onShow() {
    this._active = true
    this.setData({ loading: false })
    if (this.data.busy) return
    const generation = ++this._generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, errorMessage: view.message, requestId: view.requestId, canManage: false })
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const role = runtime.session.getUser()?.role
      this.setData({ canManage: role === 'OWNER' || role === 'ADMIN' })
      const result = await runtime.catalog.accounts(this.data.status || undefined)
      if (current()) this.setData({ items: result.items })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId, canManage: false })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  async retry() {
    if (this.data.loading || this.data.busy) return
    await this.onShow()
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

  onStatusFilter(event: WechatMiniprogram.PickerChange) {
    this.setData({ status: ['', 'ACTIVE', 'DISABLED'][Number(event.detail.value)] as '' | ResourceStatus })
    void this.onShow()
  },

  openCreate() {
    if (this.data.canManage && !this.data.loading && !this.data.busy) {
      wx.navigateTo({ url: '/pages/account-edit/index' })
    }
  },

  openEdit(event: WechatMiniprogram.TouchEvent) {
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.canManage && this.data.items.some(item => item.id === id)) {
      wx.navigateTo({ url: '/pages/account-edit/index?id=' + id })
    }
  },

  async onDelete(event: WechatMiniprogram.TouchEvent) {
    if (!this.data.canManage || this.data.busy) return
    const id = Number(event.currentTarget.dataset.id)
    const item = this.data.items.find(candidate => candidate.id === id)
    if (!item) return
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
      return
    }
    const generation = this._generation
    const operation = ++this._writeOperation
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => pageCurrent()
    this.setData({ busy: true })
    try {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '删除账户',
        content: '仅从未被账单引用的账户可删除；已有历史请停用。',
        success: result => resolve(result.confirm),
        fail: () => resolve(false),
      }))
      if (!confirmed || !current()) return
      await runtime.catalog.deleteAccount(id, item.version)
      if (current()) {
        this.setData({ busy: false })
        await this.onShow()
      }
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
    } finally {
      if (operation === this._writeOperation) this.setData({ busy: false })
    }
  },
})
