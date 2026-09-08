import { getRuntime } from '../../runtime'
import { toErrorView } from '../../utils/presentation'

type LoginViewState = 'RESTORING' | 'LOGGING_IN' | 'INVITE_REQUIRED' | 'ERROR'

Page({
  data: {
    viewState: 'RESTORING' as LoginViewState,
    busy: false,
    attempted: false,
    errorMessage: '',
    requestId: '',
  },

  onLoad(query: { inviteToken?: string } = {}) {
    if (typeof query.inviteToken === 'string') {
      wx.redirectTo({ url: '/pages/invite-accept/index?inviteToken=' + encodeURIComponent(query.inviteToken) })
      return
    }
    void this.startLogin()
  },

  openInvite() {
    if (!this.data.busy) wx.navigateTo({ url: '/pages/invite-accept/index' })
  },

  retry() {
    void this.startLogin()
  },

  async startLogin() {
    if (this.data.busy) {
      return
    }

    const viewState: LoginViewState = this.data.attempted ? 'LOGGING_IN' : 'RESTORING'
    this.setData({
      viewState,
      busy: true,
      attempted: true,
      errorMessage: '',
      requestId: '',
    })

    try {
      const result = await getRuntime().flow.start()
      if (result.destination === 'HOME') {
        wx.reLaunch({ url: '/pages/home/index' })
        return
      }
      if (result.destination === 'BOOTSTRAP') {
        wx.navigateTo({ url: '/pages/bootstrap/index' })
        return
      }
      this.setData({ viewState: 'INVITE_REQUIRED' as LoginViewState })
    } catch (error) {
      const errorView = toErrorView(error)
      this.setData({
        viewState: 'ERROR' as LoginViewState,
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      this.setData({ busy: false })
    }
  },
})
