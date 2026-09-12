import { getRuntime } from '../../runtime'
import type { Ledger, UserProfile } from '../../types/api'
import type { Entry } from '../../types/entry'
import { AppError } from '../../types/error'
import { roleLabel, toErrorView } from '../../utils/presentation'
import { navigateToPage } from '../../utils/navigation'

Page({
  _active: true,
  _generation: 0,
  _logoutOperation: 0,
  _recentOperation: 0,
  data: {
    loading: false,
    loggingOut: false,
    nickname: '',
    displayName: '',
    memberRoleLabel: '',
    ledgerName: '',
    currency: '',
    timezone: '',
    maxMembers: 0,
    canManageInvites: false,
    canManageCatalog: false,
    recentLoading: false,
    recentEntries: [] as Entry[],
    recentError: '',
    recentRequestId: '',
    errorMessage: '',
    requestId: '',
  },

  async onShow() {
    this.getTabBar?.()?.setData({ selected: 0 })

    this._active = true
    ++this._generation
    this.setData({
      loading: false, recentLoading: false,
      recentEntries: [], recentError: '', recentRequestId: '',
    })
    if (!this.data.loggingOut) await this.loadContext(true)
  },

  onHide() {
    this._active = false
    ++this._generation
  },

  onUnload() {
    this._active = false
    ++this._generation
  },

  retry() {
    void this.loadContext(true)
  },

  async loadContext(forceRefresh = false) {
    if (this.data.loading) {
      return
    }

    const runtime = getRuntime()
    const generation = this._generation
    const revision = runtime.session.getRevision()
    const isCurrent = () => this._active && generation === this._generation
      && revision === runtime.session.getRevision()
    let user = runtime.session.getUser()
    let ledger = runtime.session.getLedger()
    if (user && ledger && !forceRefresh) {
      this.showContext(user, ledger)
      await this.loadRecent(isCurrent)
      return
    }

    this.setData({ loading: true, canManageInvites: false, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!isCurrent()) return
      user = runtime.session.getUser()
      ledger = runtime.session.getLedger()
      if (!user || !ledger) {
        throw new AppError(
          'INVALID_RESPONSE',
          'SESSION_CONTEXT_MISSING',
          '登录状态不完整，请重新登录',
        )
      }
      this.showContext(user, ledger)
      await this.loadRecent(isCurrent)
    } catch (error) {
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this.setData({
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (isCurrent()) this.setData({ loading: false })
    }
  },

  async loadRecent(isCurrent?: () => boolean) {
    const runtime = getRuntime()
    const generation = this._generation
    const revision = runtime.session.getRevision()
    const operation = ++this._recentOperation
    const currentPage = isCurrent ?? (() => this._active && generation === this._generation
      && revision === runtime.session.getRevision())
    const current = () => operation === this._recentOperation && currentPage()
    this.setData({ recentLoading: true, recentError: '', recentRequestId: '' })
    try {
      const value = await runtime.entries.list({ page: 1, pageSize: 5 })
      if (current()) this.setData({ recentEntries: value.items, recentError: '', recentRequestId: '' })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ recentEntries: [], recentError: view.message, recentRequestId: view.requestId })
    } finally {
      if (current()) this.setData({ recentLoading: false })
    }
  },

  async retryRecent() {
    await this.loadRecent()
  },

  showContext(user: UserProfile, ledger: Ledger) {
    this.setData({
      nickname: user.nickname,
      displayName: user.displayName?.trim() || user.nickname,
      memberRoleLabel: roleLabel(user.role),
      ledgerName: ledger.name,
      currency: ledger.currency,
      timezone: ledger.timezone,
      maxMembers: ledger.maxMembers,
      canManageInvites: user.role === 'OWNER' || user.role === 'ADMIN',
      canManageCatalog: user.role === 'OWNER' || user.role === 'ADMIN',
      errorMessage: '',
      requestId: '',
    })
  },

  openProfile() {
    wx.navigateTo({ url: '/pages/profile/index' })
  },

  openMembers() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) wx.navigateTo({ url: '/pages/member-list/index' })
  },

  openInvites() {
    const role = getRuntime().session.getUser()?.role
    if (!this.data.loading && !this.data.loggingOut && this.data.canManageInvites && (role === 'OWNER' || role === 'ADMIN')) {
      wx.navigateTo({ url: '/pages/invite-create/index' })
    }
  },

  openCategories() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) wx.navigateTo({ url: '/pages/category-list/index' })
  },

  openAccounts() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) wx.navigateTo({ url: '/pages/account-list/index' })
  },

  openEntries() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) navigateToPage('/pages/entry-list/index')
  },

  openFavorEntry() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) {
      wx.navigateTo({ url: '/pages/entry-create/index?preset=favor' })
    }
  },

  openLifeEntry() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) wx.navigateTo({ url: '/pages/entry-create/index?preset=life' })
  },

  openEntryCreate() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) wx.navigateTo({ url: '/pages/entry-create/index' })
  },

  openStatistics() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) navigateToPage('/pages/statistics/index')
  },

  logout() {
    void this.submitLogout()
  },

  async submitLogout() {
    if (!this._active || this.data.loggingOut) {
      return
    }

    const runtime = getRuntime()
    const generation = this._generation
    const operation = ++this._logoutOperation
    const token = runtime.session.getToken()
    const revision = runtime.session.getRevision()
    const isActive = () => this._active && generation === this._generation && operation === this._logoutOperation
    const sameIdentity = () => token === runtime.session.getToken() && revision === runtime.session.getRevision()
    const wasCleared = () => runtime.session.getToken() === null && runtime.session.getRevision() === revision + 1
    this.setData({ loggingOut: true, errorMessage: '', requestId: '' })
    try {
      const result = await runtime.flow.logout()
      if (isActive() && result === 'CLEARED' && wasCleared()) {
        wx.reLaunch({ url: '/pages/login/index' })
      }
    } catch (error) {
      if (!isActive() || !sameIdentity()) return
      const errorView = toErrorView(error)
      this.setData({
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (operation === this._logoutOperation) this.setData({ loggingOut: false })
    }
  },
})
