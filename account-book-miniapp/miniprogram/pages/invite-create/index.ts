import { getRuntime } from '../../runtime'
import type { InviteStatus, InviteView } from '../../types/api'
import { toErrorView } from '../../utils/presentation'

const statuses: (InviteStatus | undefined)[] = [undefined, 'ACTIVE', 'USED', 'REVOKED', 'EXPIRED']
Page({
  data: {
    hours: '24', busy: false, loading: false, canManage: false, hasInvite: false, expiresAt: '',
    items: [] as InviteView[], page: 1, pageSize: 20, total: 0,
    filterIndex: 0, filters: ['全部状态', '有效', '已使用', '已撤销', '已过期'],
    errorMessage: '', requestId: '',
  },
  _rawToken: '',
  _identity: '',
  _generation: 0,
  _alive: true,
  _sharing: false,
  isCurrent(generation: number) {
    return this._alive && generation === this._generation
  },
  identity() {
    const session = getRuntime().session
    const user = session.getUser()
    return user && session.getToken() && (user.role === 'OWNER' || user.role === 'ADMIN')
      ? user.userId + ':' + session.getToken() : ''
  },
  clearInvite() {
    this._rawToken = ''
    this.setData({ hasInvite: false, expiresAt: '' })
    wx.hideShareMenu()
  },
  canAct() {
    if (!this._alive || !this.identity() || this.identity() !== this._identity) {
      this.clearInvite()
      this.setData({ canManage: false })
      return false
    }
    return this.data.canManage && !this.data.loading
  },
  async onShow() {
    this._alive = true
    this._sharing = false
    const generation = ++this._generation
    if (this.identity() !== this._identity) this.clearInvite()
    this.setData({ loading: true, busy: false, canManage: false, errorMessage: '', requestId: '' })
    try {
      await getRuntime().flow.refreshContext()
      if (!this._alive || generation !== this._generation) return
      const identity = this.identity()
      if (!identity || identity !== this._identity) this.clearInvite()
      this._identity = identity
      this.setData({ canManage: !!identity })
      if (identity) await this.loadHistory(1, generation)
      else this.setData({ items: [], total: 0, errorMessage: '仅所有者或管理员可管理邀请' })
    } catch (error) {
      if (this.isCurrent(generation)) {
        this.clearInvite()
        this.showError(error, generation)
      }
    } finally {
      if (this._alive && generation === this._generation) this.setData({ loading: false })
    }
  },
  onHide() {
    if (!this._sharing) {
      ++this._generation
      this.clearInvite()
    }
    this._sharing = false
  },
  onUnload() {
    this._alive = false
    ++this._generation
    this.clearInvite()
  },
  onHoursInput(event: WechatMiniprogram.Input) {
    if (!this.data.busy) this.setData({ hours: event.detail.value })
  },
  showError(error: unknown, generation: number) {
    if (!this.isCurrent(generation)) return
    if (!this.identity() || this.identity() !== this._identity) {
      this.clearInvite()
      this.setData({ canManage: false, items: [] })
    }
    const view = toErrorView(error)
    this.setData({ errorMessage: view.message, requestId: view.requestId })
  },
  async loadHistory(page: number, expectedGeneration?: number) {
    const generation = expectedGeneration ?? this._generation
    const result = await getRuntime().invites.list({ page, pageSize: 20, status: statuses[this.data.filterIndex] })
    if (!this._alive || generation !== this._generation) return
    if (this.identity() !== this._identity) {
      this.clearInvite()
      this.setData({ canManage: false, items: [], total: 0 })
      return
    }
    this.setData({ items: result.items, page: result.page, pageSize: result.pageSize, total: result.total })
  },
  async onCreate() {
    if (!this.canAct() || this.data.busy) return
    const generation = this._generation
    const identity = this._identity
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    this.clearInvite()
    try {
      const result = await getRuntime().invites.create(Number(this.data.hours))
      if (!this.isCurrent(generation)) return
      if (this.identity() !== identity) {
        this.clearInvite()
        this.setData({ canManage: false, items: [], total: 0 })
        return
      }
      this._rawToken = result.token
      this.setData({ hasInvite: true, expiresAt: result.expiresAt })
      wx.showShareMenu({ menus: ['shareAppMessage'] })
      await this.loadHistory(1, generation)
    } catch (error) {
      this.showError(error, generation)
    } finally {
      if (this.isCurrent(generation)) this.setData({ busy: false })
    }
  },
  onCopy() {
    if (this.canAct() && !this.data.busy && this._rawToken) wx.setClipboardData({ data: this._rawToken })
  },
  onShareAppMessage() {
    if (!this.canAct() || this.data.busy || !this._rawToken) {
      return { title: '加入共享账本', path: '/pages/invite-accept/index' }
    }
    this._sharing = true
    return { title: '邀请你加入共享账本', path: '/pages/invite-accept/index?inviteToken=' + encodeURIComponent(this._rawToken) }
  },
  async changePage(page: number) {
    if (!this.canAct() || this.data.busy) return
    const generation = this._generation
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    try { await this.loadHistory(page, generation) } catch (error) { this.showError(error, generation) }
    finally { if (this.isCurrent(generation)) this.setData({ busy: false }) }
  },
  async nextPage() {
    if (this.data.page * this.data.pageSize < this.data.total) await this.changePage(this.data.page + 1)
  },
  async previousPage() {
    if (this.data.page > 1) await this.changePage(this.data.page - 1)
  },
  async onFilterChange(event: WechatMiniprogram.PickerChange) {
    if (!this.canAct() || this.data.busy) return
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= statuses.length) return
    this.setData({ filterIndex: index })
    await this.changePage(1)
  },
  async onRevoke(event: WechatMiniprogram.TouchEvent) {
    if (!this.canAct() || this.data.busy) return
    const id = Number(event.currentTarget.dataset.id)
    if (!this.data.items.some(item => item.id === id && item.status === 'ACTIVE')) return
    const identity = this.identity()
    const revision = getRuntime().session.getRevision()
    const generation = this._generation
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    try {
      const confirmed = await new Promise<boolean>((resolve, reject) => wx.showModal({
        title: '撤销邀请', content: '撤销后，该邀请码和分享卡片将无法再用于加入账本。', confirmText: '确认撤销',
        success: result => resolve(result.confirm), fail: reject,
      }))
      if (!confirmed || generation !== this._generation || identity !== this.identity()
        || revision !== getRuntime().session.getRevision() || !this.canAct()
        || !this.data.items.some(item => item.id === id && item.status === 'ACTIVE')) return
      await getRuntime().invites.revoke(id)
      if (!this.isCurrent(generation)) return
      this.clearInvite()
      await this.loadHistory(1, generation)
    } catch (error) { this.showError(error, generation) }
    finally { if (this.isCurrent(generation)) this.setData({ busy: false }) }
  },
})
