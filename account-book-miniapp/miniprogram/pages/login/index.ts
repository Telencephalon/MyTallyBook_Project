import { getRuntime } from '../../runtime'
import { toErrorView } from '../../utils/presentation'

type LoginViewState = 'RESTORING' | 'LOGGING_IN' | 'INVITE_REQUIRED' | 'ERROR'

Page({
  _active: true,
  _generation: 0,
  _resumeLoginOnShow: false,
  _publicNavigationBusy: false,
  data: {
    viewState: 'RESTORING' as LoginViewState,
    busy: false,
    attempted: false,
    errorMessage: '',
    requestId: '',
  },

  onLoad(query: { inviteToken?: string } = {}) {
    this._active = true
    ++this._generation
    if (typeof query.inviteToken === 'string') {
      this._active = false
      ++this._generation
      wx.redirectTo({ url: '/pages/invite-accept/index?inviteToken=' + encodeURIComponent(query.inviteToken) })
      return
    }
    void this.startLogin()
  },

  onShow() {
    if (!this._resumeLoginOnShow) return
    this._active = true
    ++this._generation
    this._resumeLoginOnShow = false
    this._publicNavigationBusy = false
    this.setData({ busy: false })
    void this.startLogin()
  },

  onHide() {
    this._active = false
    ++this._generation
    this._resumeLoginOnShow = true
    this.setData({ busy: false })
  },

  onUnload() {
    this._active = false
    ++this._generation
    this._resumeLoginOnShow = false
  },

  openAbout() {
    this.openPublicPage('/pages/about/index')
  },

  openPrivacy() {
    this.openPublicPage('/pages/privacy/index')
  },

  openPublicPage(url: string) {
    if (this._publicNavigationBusy) return
    this._publicNavigationBusy = true
    this._active = false
    ++this._generation
    this._resumeLoginOnShow = true
    this.setData({ busy: false })
    wx.navigateTo({
      url,
      fail: () => {
        if (!this._publicNavigationBusy) return
        this._publicNavigationBusy = false
        this._active = true
        ++this._generation
        this._resumeLoginOnShow = false
        void this.startLogin()
      },
    })
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

    const generation = this._generation
    const isCurrent = () => this._active && generation === this._generation
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
      if (!isCurrent()) return
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
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this.setData({
        viewState: 'ERROR' as LoginViewState,
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (isCurrent()) this.setData({ busy: false })
    }
  },
})
