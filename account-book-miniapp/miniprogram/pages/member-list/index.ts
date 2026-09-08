import { getRuntime } from '../../runtime'
import type { MemberView } from '../../types/api'
import { roleLabel, toErrorView } from '../../utils/presentation'

Page({
  data: {
    items: [] as (MemberView & { name: string; roleLabel: string })[],
    activeCount: 0, maxMembers: 0, loading: false, busy: false, canLeave: false,
    errorMessage: '', requestId: '',
  },
  _alive: true,
  _generation: 0,
  _identity: '',
  identity() {
    const session = getRuntime().session
    return session.getUser() && session.getToken() ? session.getUser()!.userId + ':' + session.getToken() : ''
  },
  async onShow() {
    this._alive = true
    ++this._generation
    await this.loadMembers()
  },
  onUnload() { this._alive = false; ++this._generation },
  async loadMembers() {
    if (this.data.loading) return
    this.setData({ loading: true, canLeave: false, errorMessage: '', requestId: '' })
    try {
      await getRuntime().flow.refreshContext()
      const identity = this.identity()
      const result = await getRuntime().members.list()
      if (!this._alive || !identity || identity !== this.identity()) return
      this._identity = identity
      this.setData({
        items: result.items.map(item => ({ ...item, name: item.displayName?.trim() || item.nickname, roleLabel: roleLabel(item.role) })),
        activeCount: result.activeCount, maxMembers: result.maxMembers,
        canLeave: getRuntime().session.getUser()?.role !== 'OWNER',
      })
    } catch (error) { this.showError(error) }
    finally { if (this._alive) this.setData({ loading: false }) }
  },
  showError(error: unknown) {
    if (!this._alive) return
    const view = toErrorView(error)
    this.setData({ errorMessage: view.message, requestId: view.requestId })
  },
  openMember(event: WechatMiniprogram.TouchEvent) {
    if (this.data.busy || this.data.loading || !this.identity() || this.identity() !== this._identity) return
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.items.some(item => item.memberId === id)) wx.navigateTo({ url: '/pages/member-edit/index?memberId=' + id })
  },
  async onLeave() {
    const runtime = getRuntime()
    const user = runtime.session.getUser()
    if (this.data.busy || this.data.loading || !this.data.canLeave || !user || user.role === 'OWNER' || this.identity() !== this._identity) return
    const identity = this.identity()
    const revision = runtime.session.getRevision()
    const generation = this._generation
    const memberId = user.memberId
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    try {
      const confirmed = await new Promise<boolean>((resolve, reject) => wx.showModal({
        title: '退出账本', content: '退出后将失去账本访问权限，所有会话失效；重新加入需要新的邀请。历史记录会保留。这不是退出登录。',
        confirmText: '确认退出', success: result => resolve(result.confirm), fail: reject,
      }))
      if (!confirmed || !this._alive || generation !== this._generation || this.identity() !== identity
        || revision !== runtime.session.getRevision() || runtime.session.getUser()?.memberId !== memberId
        || runtime.session.getUser()?.role === 'OWNER') return
      await runtime.members.remove(memberId)
      if (this._alive && !runtime.session.getToken()) wx.reLaunch({ url: '/pages/login/index' })
    } catch (error) { this.showError(error) }
    finally { if (this._alive) this.setData({ busy: false }) }
  },
})
