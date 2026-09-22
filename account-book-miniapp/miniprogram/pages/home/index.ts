import { getRuntime } from '../../runtime'
import { APP_INFO } from '../../config/app-info'
import type { Ledger, UserProfile } from '../../types/api'
import type { CreatorOption, Entry } from '../../types/entry'
import { creatorOptions, creatorScopeIdentity } from '../../utils/creator-scope'
import { AppError } from '../../types/error'
import { roleLabel, toErrorView } from '../../utils/presentation'
import { navigateToPage } from '../../utils/navigation'
import { captureTabSnapshot, canReuseTabSnapshot, matchesTabSnapshot, type TabSnapshot } from '../../utils/tab-snapshot'

Page({
  onShareAppMessage() {
    return { title: APP_INFO.appName, path: '/pages/login/index' }
  },

  _active: true,
  _generation: 0,
  _logoutOperation: 0,
  _recentOperation: 0,
  _contextOperation: 0,
  _recentSnapshot: null as TabSnapshot | null,
  _creatorIdentity: '',
  data: {
    loading: false,
    loggingOut: false,
    nickname: '',
    displayName: '',
    memberRoleLabel: '',
    ledgerName: '',
    currency: '',
    timezone: '',
    maxMembers: null as number | null,
    canSelectCreator: false, createdBy: 0, creatorName: '全部成员', creators: [] as CreatorOption[],
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
    this.syncCreatorScope()
    this.setData({
      loading: false, recentLoading: false, canManageCatalog: false, canSelectCreator: false,
      recentError: '', recentRequestId: '',
    })
    if (!this.data.loggingOut) await this.loadContext(true, true)
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

  async loadContext(forceRefresh = false, tabReturn = false) {
    if (this.data.loading) {
      return
    }

    const runtime = getRuntime()
    const generation = this._generation
    const operation = ++this._contextOperation
    const revision = runtime.session.getRevision()
    const isCurrent = () => {
      if (!this._active || generation !== this._generation || operation !== this._contextOperation) return false
      if (revision !== runtime.session.getRevision()) {
        this.syncCreatorScope()
        this.setData({ loading: false, canManageCatalog: false, canManageInvites: false })
        return false
      }
      return true
    }
    let user = runtime.session.getUser()
    let ledger = runtime.session.getLedger()
    if (user && ledger && !forceRefresh) {
      this.showContext(user, ledger)
      await this.loadRecent(isCurrent)
      return
    }

    this.setData({ loading: true, canManageInvites: false, canManageCatalog: false, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext(tabReturn ? { reusePending: true } : undefined)
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
      this.setData({ loading: false })
      await this.loadRecent(isCurrent, tabReturn)
    } catch (error) {
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this._recentSnapshot = null
      this.setData({
        recentEntries: [], canSelectCreator: false, canManageCatalog: false, canManageInvites: false,
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (isCurrent()) this.setData({ loading: false })
    }
  },

  async loadRecent(isCurrent?: () => boolean, tabReturn = false) {
    const runtime = getRuntime()
    this.syncCreatorScope()
    const query = String(this.data.createdBy)
    if (tabReturn && canReuseTabSnapshot(this._recentSnapshot, runtime.session, query)) return
    const preserve = tabReturn && matchesTabSnapshot(this._recentSnapshot, runtime.session, query)
    const snapshot = captureTabSnapshot(runtime.session, query)
    this._recentSnapshot = null
    const identity = this._creatorIdentity
    const generation = this._generation
    const revision = runtime.session.getRevision()
    const operation = ++this._recentOperation
    const currentPage = isCurrent ?? (() => this._active && generation === this._generation
      && revision === runtime.session.getRevision())
    const current = () => {
      if (!this._active) return false
      if (identity !== creatorScopeIdentity(runtime.session)) { this.syncCreatorScope(); return false }
      return currentPage() && operation === this._recentOperation
    }
    this.setData({ recentLoading: true, ...(!preserve ? { recentEntries: [] } : {}), recentError: '', recentRequestId: '' })
    try {
      const [value, creators] = await Promise.all([
        runtime.entries.list({ page: 1, pageSize: 5, ...(this.data.createdBy ? { createdBy: this.data.createdBy } : {}) }),
        this.data.canSelectCreator ? runtime.entries.creators() : Promise.resolve({ items: [] }),
      ])
      if (current()) {
        this._recentSnapshot = snapshot
        this.setData({ recentEntries: value.items,
        creatorName: creators.items.find(item => item.userId === this.data.createdBy)?.displayName || '全部成员',
        creators: this.data.canSelectCreator ? creatorOptions(creators.items) : [], recentError: '', recentRequestId: '' })
      }
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

  syncCreatorScope() {
    const session = getRuntime().session
    const identity = creatorScopeIdentity(session)
    const changed = this._creatorIdentity !== identity
    this._creatorIdentity = identity
    if (changed) this._recentSnapshot = null
    this.setData({ canSelectCreator: session.getUser()?.role === 'OWNER',
      ...(changed ? { createdBy: 0, creatorName: '全部成员', creators: [], recentEntries: [], recentLoading: false } : {}) })
  },

  async onCreator(event: WechatMiniprogram.PickerChange) {
    this.syncCreatorScope()
    if (!this.data.canSelectCreator) return
    const selected = this.data.creators[Number(event.detail.value)]
    if (!selected) return
    this.setData({ createdBy: selected.userId, creatorName: selected.displayName })
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
      canManageCatalog: user.role === 'OWNER',
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
    if (!this.data.loading && !this.data.loggingOut && this.data.canManageCatalog && getRuntime().session.getUser()?.role === 'OWNER') wx.navigateTo({ url: '/pages/category-list/index' })
  },

  openAccounts() {
    if (!this.data.loading && !this.data.loggingOut && this.data.canManageCatalog && getRuntime().session.getUser()?.role === 'OWNER') wx.navigateTo({ url: '/pages/account-list/index' })
  },

  openEntries() {
    if (!this.data.loading && !this.data.loggingOut && getRuntime().session.getUser()) navigateToPage('/pages/entry-list/index')
  },

  openRecentEntry(event: WechatMiniprogram.TouchEvent) {
    if (!this._active || this.data.loading || this.data.loggingOut) return
    this.syncCreatorScope()
    const id = Number(event.currentTarget.dataset.id)
    if (getRuntime().session.getUser() && this.data.recentEntries.some(item => item.id === id)) {
      wx.navigateTo({ url: `/pages/entry-detail/index?id=${id}` })
    }
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
