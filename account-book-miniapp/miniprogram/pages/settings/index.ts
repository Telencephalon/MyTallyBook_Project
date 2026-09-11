import { getRuntime } from '../../runtime'
import { APP_INFO } from '../../config/app-info'
import type { Ledger, UserProfile } from '../../types/api'
import { roleLabel, toErrorView } from '../../utils/presentation'

Page({
  _active: true,
  _generation: 0,
  _logoutOperation: 0,
  data: {
    loading: false,
    loggingOut: false,
    nickname: '',
    roleLabel: '',
    ledgerName: '',
    version: APP_INFO.version,
    canManageInvites: false,
    errorMessage: '',
    requestId: '',
  },

  async onShow() {
    this._active = true
    ++this._generation
    this.setData({ loading: false })
    if (!this.data.loggingOut) await this.loadContext()
  },

  onHide() {
    this._active = false
    ++this._generation
  },

  onUnload() {
    this._active = false
    ++this._generation
  },

  async loadContext() {
    if (!this._active || this.data.loading) return

    const runtime = getRuntime()
    const generation = this._generation
    const revision = runtime.session.getRevision()
    const isCurrent = () => this._active && generation === this._generation
      && revision === runtime.session.getRevision()
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!isCurrent()) return
      this.showContext(runtime.session.getUser(), runtime.session.getLedger())
    } catch (error) {
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this.setData({ errorMessage: errorView.message, requestId: errorView.requestId })
      this.showContext(runtime.session.getUser(), runtime.session.getLedger(), false)
    } finally {
      if (isCurrent()) this.setData({ loading: false })
    }
  },

  showContext(user: UserProfile | null, ledger: Ledger | null, clearError = true) {
    this.setData({
      nickname: user ? user.displayName?.trim() || user.nickname : '',
      roleLabel: user ? roleLabel(user.role) : '',
      ledgerName: ledger?.name ?? '',
      canManageInvites: user?.role === 'OWNER' || user?.role === 'ADMIN',
      ...(clearError ? { errorMessage: '', requestId: '' } : {}),
    })
  },

  openProfile() {
    if (this.canOpenAuthenticatedPage()) wx.navigateTo({ url: '/pages/profile/index' })
  },

  openMembers() {
    if (this.canOpenAuthenticatedPage()) wx.navigateTo({ url: '/pages/member-list/index' })
  },

  openInvites() {
    const role = getRuntime().session.getUser()?.role
    if (this.canOpenAuthenticatedPage() && this.data.canManageInvites && (role === 'OWNER' || role === 'ADMIN')) {
      wx.navigateTo({ url: '/pages/invite-create/index' })
    }
  },

  openCategories() {
    if (this.canOpenAuthenticatedPage()) wx.navigateTo({ url: '/pages/category-list/index' })
  },

  openAccounts() {
    if (this.canOpenAuthenticatedPage()) wx.navigateTo({ url: '/pages/account-list/index' })
  },

  openAbout() {
    if (!this.data.loggingOut) wx.navigateTo({ url: '/pages/about/index' })
  },

  openPrivacy() {
    if (!this.data.loggingOut) wx.navigateTo({ url: '/pages/privacy/index' })
  },

  canOpenAuthenticatedPage(): boolean {
    return !this.data.loading && !this.data.loggingOut && Boolean(getRuntime().session.getUser())
  },

  async logout() {
    if (!this._active || this.data.loggingOut) return

    const runtime = getRuntime()
    const generation = this._generation
    const operation = ++this._logoutOperation
    const token = runtime.session.getToken()
    const revision = runtime.session.getRevision()
    const isActive = () => this._active && generation === this._generation
      && operation === this._logoutOperation
    const sameIdentity = () => token === runtime.session.getToken()
      && revision === runtime.session.getRevision()
    const wasCleared = () => runtime.session.getToken() === null
      && runtime.session.getRevision() === revision + 1
    this.setData({ loggingOut: true, errorMessage: '', requestId: '' })
    try {
      const result = await runtime.flow.logout()
      if (isActive() && result === 'CLEARED' && wasCleared()) {
        wx.reLaunch({ url: '/pages/login/index' })
      }
    } catch (error) {
      if (!isActive() || !sameIdentity()) return
      const errorView = toErrorView(error)
      this.setData({ errorMessage: errorView.message, requestId: errorView.requestId })
    } finally {
      if (operation === this._logoutOperation) this.setData({ loggingOut: false })
    }
  },
})
