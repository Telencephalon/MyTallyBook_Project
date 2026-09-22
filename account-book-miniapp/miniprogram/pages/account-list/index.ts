import { getRuntime } from '../../runtime'
import { creatorScopeIdentity } from '../../utils/creator-scope'
import type { ResourceStatus } from '../../types/catalog'
import { accountCard } from '../../utils/catalog-presentation'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

Page({
  data: {
    items: [] as ReturnType<typeof accountCard>[],
    openMenuId: 0,
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
    this.setData({ loading: false, openMenuId: 0 })
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
    const guard = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    let identity = ''
    const current = () => {
      if (!this._active || generation !== this._generation) return false
      if (identity && identity !== creatorScopeIdentity(runtime.session)) {
        this.setData({ items: [], canManage: false, loading: false }); return false
      }
      return guard()
    }
    this.setData({ items: [], canManage: false, loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      identity = creatorScopeIdentity(runtime.session)
      const role = runtime.session.getUser()?.role
      this.setData({ canManage: role === 'OWNER' })
      const result = await runtime.catalog.accounts(this.data.status || undefined)
      if (current()) this.setData({ items: result.items.map(accountCard) })
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
    this.setData({ loading: false, openMenuId: 0 })
  },

  onUnload() {
    this._active = false
    ++this._generation
    this.setData({ loading: false, openMenuId: 0 })
  },

  closeMenu() {
    if (this.data.openMenuId) this.setData({ openMenuId: 0 })
  },

  onPageScroll() {
    this.closeMenu()
  },

  toggleMenu(event: WechatMiniprogram.TouchEvent) {
    if (!this._active || this.data.loading || this.data.busy || !this.data.canManage
      || getRuntime().session.getUser()?.role !== 'OWNER') return
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.items.some(item => item.id === id)) {
      this.setData({ openMenuId: this.data.openMenuId === id ? 0 : id })
    }
  },

  onStatusFilter(event: WechatMiniprogram.PickerChange) {
    this.setData({ status: ['', 'ACTIVE', 'DISABLED'][Number(event.detail.value)] as '' | ResourceStatus })
    void this.onShow()
  },

  openCreate() {
    if (getRuntime().session.getUser()?.role === 'OWNER' && this.data.canManage && !this.data.loading && !this.data.busy) {
      this.closeMenu()
      wx.navigateTo({ url: '/pages/account-edit/index' })
    }
  },

  openEdit(event: WechatMiniprogram.TouchEvent) {
    if (!this._active || this.data.loading || this.data.busy) return
    const id = Number(event.currentTarget.dataset.id)
    if (getRuntime().session.getUser()?.role === 'OWNER' && this.data.canManage && this.data.items.some(item => item.id === id)) {
      this.closeMenu()
      wx.navigateTo({ url: '/pages/account-edit/index?id=' + id })
    }
  },

  async onDelete(event: WechatMiniprogram.TouchEvent) {
    if (!this._active || this.data.loading) return
    if (getRuntime().session.getUser()?.role !== 'OWNER' || !this.data.canManage || this.data.busy) return
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
    this.setData({ busy: true, openMenuId: 0 })
    try {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '删除账户',
        content: '仅从未被账单引用的账户可删除；已有历史请停用。',
        success: result => resolve(result.confirm),
        fail: () => resolve(false),
      }))
      if (!confirmed || !current() || runtime.session.getUser()?.role !== 'OWNER') return
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
