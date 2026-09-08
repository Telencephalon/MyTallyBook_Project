import { getRuntime } from '../../runtime'
import { AppError } from '../../types/error'
import { toErrorView } from '../../utils/presentation'

Page({
  data: { inviteToken: '', busy: false, alreadyMember: false, errorMessage: '', requestId: '' },
  _alive: true,
  _generation: 0,
  onLoad(query: { inviteToken?: string }) {
    this._alive = true
    ++this._generation
    // WeChat supplies decoded query values. Never decode a second time or follow a supplied URL.
    this.setData({ inviteToken: typeof query.inviteToken === 'string' ? query.inviteToken : '' })
  },
  onTokenInput(event: WechatMiniprogram.Input) {
    if (!this.data.busy) this.setData({ inviteToken: event.detail.value, alreadyMember: false })
  },
  onUnload() {
    this._alive = false
    ++this._generation
    this.setData({ inviteToken: '' })
  },
  async onAccept() {
    if (this.data.busy || !this._alive) return
    const generation = this._generation
    const isActive = () => this._alive && generation === this._generation
    this.setData({ busy: true, errorMessage: '', requestId: '', alreadyMember: false })
    try {
      await getRuntime().invites.accept(this.data.inviteToken, isActive)
      if (!isActive()) return
      this.setData({ inviteToken: '' })
      if (this._alive) wx.reLaunch({ url: '/pages/home/index' })
    } catch (error) {
      if (isActive()) {
        const view = toErrorView(error)
        this.setData({ errorMessage: view.message, requestId: view.requestId, alreadyMember: error instanceof AppError && error.code === 'ALREADY_MEMBER' })
      }
    } finally {
      if (isActive()) this.setData({ busy: false })
    }
  },
  openEntry() {
    if (this.data.busy) return
    wx.reLaunch({ url: getRuntime().session.getToken() ? '/pages/home/index' : '/pages/login/index' })
  },
})
