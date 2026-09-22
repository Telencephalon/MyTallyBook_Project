import { getRuntime } from '../../runtime'
import { APP_INFO } from '../../config/app-info'
import type { AccountStatisticsItem, DailyStatisticsItem, MonthlySummary, RankingItem, RankingDirection, StatisticsQuery } from '../../types/statistics'
import { shanghaiToday } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'
import type { CreatorOption } from '../../types/entry'
import { creatorOptions, creatorScopeIdentity } from '../../utils/creator-scope'
import { canReuseTabSnapshot, captureTabSnapshot, matchesTabSnapshot, type TabSnapshot } from '../../utils/tab-snapshot'

type LoadState = 'loading' | 'ready' | 'empty' | 'error'
type RankingView = RankingItem & { width: number }
type Direction = Exclude<RankingDirection, 'ALL'>
type RankingGroup = { entryType: Direction; label: string; total: string; items: RankingView[] }
type Scope = 'MONTH' | 'ALL' | 'RANGE'

function currentMonth(): string {
  return shanghaiToday().slice(0, 7)
}

function rankingViews(items: RankingItem[]): RankingView[] {
  return items.map(item => {
    const percentage = Number(item.percentage)
    return { ...item, width: Number.isFinite(percentage) ? Math.max(0, Math.min(100, percentage)) : 0 }
  })
}

function validDate(value: string): boolean {
  if (!/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value &&
    value >= '1000-01-01' && value <= '9999-12-31'
}

function cloneQuery(query: string | StatisticsQuery): string | StatisticsQuery {
  return typeof query === 'string' ? query : { ...query }
}

async function rankingGroups(
  statistics: ReturnType<typeof getRuntime>['statistics'],
  dimension: 'categories' | 'members',
  query: string | StatisticsQuery,
  direction: RankingDirection,
): Promise<RankingGroup[]> {
  const directions: Direction[] = direction === 'ALL' ? ['INCOME', 'EXPENSE'] : [direction]
  return Promise.all(directions.map(async entryType => {
    const result = await statistics[dimension](cloneQuery(query), entryType)
    return { entryType, label: entryType === 'INCOME' ? '收入' : '支出',
      total: result.total, items: rankingViews(result.items) }
  }))
}

function withPage(query: string | StatisticsQuery, page: number): string | StatisticsQuery {
  if (typeof query === 'string') return page === 1 ? query : { month: query, page }
  return { ...query, page }
}

function labelFor(scope: Scope, month: string, startDate: string, endDate: string): string {
  if (scope === 'ALL') return '全部'
  if (scope === 'RANGE') return `${startDate} 至 ${endDate}`
  return month
}

Page({
  onShareAppMessage() {
    return { title: APP_INFO.appName, path: '/pages/login/index' }
  },

  data: {
    canSelectCreator: false, createdBy: 0, creatorName: '全部成员', creators: [] as CreatorOption[],
    month: currentMonth(),
    scopeIndex: 0,
    scopeLabels: ['月度', '全部', '自定义'],
    customStartDate: currentMonth() + '-01',
    customEndDate: shanghaiToday(),
    appliedScope: 'MONTH' as Scope,
    appliedStartDate: '',
    appliedEndDate: '',
    appliedRangeLabel: currentMonth(),
    dailyPage: 1,
    dailyPageSize: 31,
    dailyTotalDays: 0,
    dailyTotalPages: 0,
    dailyHasNext: false,
    dailyDetailsExpanded: false,
    entryType: 'ALL' as RankingDirection,
    directionIndex: 2,
    directionLabels: ['支出', '收入', '全部'],
    summary: null as MonthlySummary | null,
    daily: [] as DailyStatisticsItem[],
    categoryGroups: [] as RankingGroup[],
    accountItems: [] as AccountStatisticsItem[],
    memberGroups: [] as RankingGroup[],
    loading: false,
    loadState: 'loading' as LoadState,
    errorMessage: '',
    requestId: '',
  },
  _defaultMonthAtDefinition: currentMonth(),
  _hasExplicitScope: false,
  _active: true,
  _generation: 0,
  _fullLoadGeneration: 0,
  _creatorIdentity: '',
  _snapshot: null as TabSnapshot | null,
  _displaySnapshot: null as TabSnapshot | null,

  syncCreatorScope() {
    const session = getRuntime().session
    const identity = creatorScopeIdentity(session)
    const changed = this._creatorIdentity !== identity
    this._creatorIdentity = identity
    if (changed) { this._snapshot = null; this._displaySnapshot = null }
    this.setData({ canSelectCreator: session.getUser()?.role === 'OWNER',
      ...(changed ? { createdBy: 0, creatorName: '全部成员', creators: [], summary: null,
        daily: [], categoryGroups: [], accountItems: [], memberGroups: [],
        dailyPage: 1, dailyTotalDays: 0, dailyTotalPages: 0, dailyHasNext: false, loading: false,
        loadState: 'empty' as LoadState, errorMessage: '', requestId: '' } : {}) })
    return changed
  },

  creatorGuard(pageCurrent: () => boolean) {
    const identity = this._creatorIdentity
    return () => {
      if (!this._active) return false
      if (identity !== creatorScopeIdentity(getRuntime().session)) { this.syncCreatorScope(); return false }
      return pageCurrent()
    }
  },

  async onCreator(event: WechatMiniprogram.PickerChange) {
    this.syncCreatorScope()
    if (!this.data.canSelectCreator) return
    const selected = this.data.creators[Number(event.detail.value)]
    if (!selected) return
    this.setData({ createdBy: selected.userId, creatorName: selected.displayName, dailyPage: 1 })
    await this.loadAll()
  },

  async onShow() {
    this.getTabBar?.()?.setData({ selected: 2 })

    this._active = true
    if (!this._hasExplicitScope && this.data.month === this._defaultMonthAtDefinition && this.data.month !== currentMonth()) {
      const month = currentMonth()
      this.setData({
        month,
        customStartDate: `${month}-01`,
        customEndDate: shanghaiToday(),
        appliedScope: 'MONTH' as Scope,
        appliedStartDate: '',
        appliedEndDate: '',
        appliedRangeLabel: month,
        dailyPage: 1,
      })
    }
    this.setData({ loading: false })
    await this.loadAll(true)
  },

  onHide() {
    this._active = false
    ++this._generation
  },

  onUnload() {
    this._active = false
    ++this._generation
  },

  async retry() {
    await this.loadAll()
  },

  toggleDailyDetails() {
    this.setData({ dailyDetailsExpanded: !this.data.dailyDetailsExpanded })
  },

  snapshotQuery(dailyPage?: number): string {
    return JSON.stringify({ query: this.currentQuery(), dailyPage: dailyPage ?? this.data.dailyPage,
      dailyPageSize: this.data.dailyPageSize, entryType: this.data.entryType })
  },

  async loadAll(reuseSnapshot = false) {
    const generation = ++this._generation
    this._fullLoadGeneration = generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      this._fullLoadGeneration = 0
      return
    }
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.syncCreatorScope()
    let current = pageCurrent
    const preserveDisplay = reuseSnapshot && matchesTabSnapshot(this._displaySnapshot, runtime.session, this.snapshotQuery())
    if (!reuseSnapshot) this._snapshot = null
    if (!preserveDisplay) this._displaySnapshot = null
    this.setData({
      loading: true, errorMessage: '', requestId: '',
      ...(!preserveDisplay ? { loadState: 'loading' as LoadState,
        summary: null, daily: [], categoryGroups: [],
        accountItems: [], memberGroups: [] } : {}),
    })
    try {
      if (reuseSnapshot) await runtime.flow.refreshContext({ reusePending: true })
      else await runtime.flow.refreshContext()
      if (!current()) { this.syncCreatorScope(); return }
      this.syncCreatorScope()
      current = this.creatorGuard(pageCurrent)
      const query = this.snapshotQuery()
      if (reuseSnapshot && canReuseTabSnapshot(this._snapshot, runtime.session, query)) return
      const snapshot = captureTabSnapshot(runtime.session, query)
      this._snapshot = null
      this.setData({ loading: true,
        ...(!matchesTabSnapshot(this._displaySnapshot, runtime.session, query) ? { loadState: 'loading' as LoadState } : {}) })
      const baseQuery = this.currentQuery()
      const dailyQuery = withPage(baseQuery, this.data.dailyPage)
      const [summary, daily, categories, accounts, members, creators] = await Promise.all([
        runtime.statistics.summary(cloneQuery(baseQuery)),
        runtime.statistics.daily(cloneQuery(dailyQuery)),
        rankingGroups(runtime.statistics, 'categories', baseQuery, this.data.entryType),
        runtime.statistics.accounts(cloneQuery(baseQuery)),
        rankingGroups(runtime.statistics, 'members', baseQuery, this.data.entryType),
        this.data.canSelectCreator ? runtime.entries.creators() : Promise.resolve({ items: [] }),
      ])
      if (!current()) return
      this.setData({
        creators: this.data.canSelectCreator ? creatorOptions(creators.items) : [],
        creatorName: creators.items.find(item => item.userId === this.data.createdBy)?.displayName || '全部成员',
        month: summary.month ?? this.data.month,
        summary,
        daily: daily.items,
        appliedRangeLabel: labelFor(this.data.appliedScope, summary.month ?? this.data.month,
          this.data.appliedStartDate || summary.startDate || '',
          this.data.appliedEndDate || summary.endDate || ''),
        dailyPage: daily.page,
        dailyPageSize: daily.pageSize,
        dailyTotalDays: daily.totalDays,
        dailyTotalPages: daily.totalPages,
        dailyHasNext: daily.hasNext,
        categoryGroups: categories,
        accountItems: accounts.items,
        memberGroups: members,
        loadState: summary.entryCount === 0 ? 'empty' : 'ready',
        errorMessage: '',
        requestId: '',
      })
      this._snapshot = snapshot
      this._displaySnapshot = snapshot
    } catch (error) {
      if (!current()) { this.syncCreatorScope(); return }
      this._snapshot = null
      this._displaySnapshot = null
      const view = toErrorView(error)
      this.setData({
        summary: null,
        daily: [],
        categoryGroups: [],
        accountItems: [],
        memberGroups: [],
        loadState: 'error',
        errorMessage: view.message,
        requestId: view.requestId,
      })
    } finally {
      if (this._fullLoadGeneration === generation) this._fullLoadGeneration = 0
      if (current()) this.setData({ loading: false })
    }
  },

  async loadRankings() {
    const generation = ++this._generation
    // Partial reads cannot make the untouched summary, accounts or daily data fresh.
    this._snapshot = null
    this._displaySnapshot = null
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      return
    }
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    if (this.syncCreatorScope()) { await this.loadAll(); return }
    let current = pageCurrent
    this.setData({ loading: true, categoryGroups: [], memberGroups: [], errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      if (this.syncCreatorScope()) { await this.loadAll(); return }
      current = this.creatorGuard(pageCurrent)
      const snapshot = captureTabSnapshot(runtime.session, this.snapshotQuery())
      const baseQuery = this.currentQuery()
      const [categories, members] = await Promise.all([
        rankingGroups(runtime.statistics, 'categories', baseQuery, this.data.entryType),
        rankingGroups(runtime.statistics, 'members', baseQuery, this.data.entryType),
      ])
      if (!current()) return
      this.setData({
        categoryGroups: categories,
        memberGroups: members,
        loadState: this.data.summary?.entryCount === 0 ? 'empty' : 'ready',
      })
      this._displaySnapshot = snapshot
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({
        categoryGroups: [], memberGroups: [],
        loadState: 'error', errorMessage: view.message, requestId: view.requestId,
      })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  async onMonthChange(event: WechatMiniprogram.PickerChange) {
    const month = String(event.detail.value)
    this._hasExplicitScope = true
    this.setData({
      month, scopeIndex: 0, appliedScope: 'MONTH' as Scope, dailyPage: 1,
      appliedRangeLabel: month,
    })
    await this.loadAll()
  },

  async onScopeChange(event: WechatMiniprogram.PickerChange) {
    const scopeIndex = Number(event.detail.value)
    this._hasExplicitScope = true
    if (scopeIndex === 1) {
      this.setData({ scopeIndex, appliedScope: 'ALL' as Scope, dailyPage: 1, appliedRangeLabel: '全部' })
      await this.loadAll()
      return
    }
    if (scopeIndex === 2) {
      this.setData({ scopeIndex })
      return
    }
    this.setData({
      scopeIndex: 0, appliedScope: 'MONTH' as Scope, dailyPage: 1,
      appliedRangeLabel: this.data.month,
    })
    await this.loadAll()
  },

  onCustomStartChange(event: WechatMiniprogram.PickerChange) {
    this.setData({ customStartDate: String(event.detail.value) })
  },

  onCustomEndChange(event: WechatMiniprogram.PickerChange) {
    this.setData({ customEndDate: String(event.detail.value) })
  },

  async onApplyCustomRange() {
    const startDate = this.data.customStartDate
    const endDate = this.data.customEndDate
    if (!validDate(startDate) || !validDate(endDate) || endDate < startDate) {
      this.setData({ loadState: this.data.summary ? this.data.loadState : 'error', errorMessage: '请选择有效的开始和结束日期', requestId: '' })
      return
    }
    this.setData({
      scopeIndex: 2, appliedScope: 'RANGE' as Scope, appliedStartDate: startDate, appliedEndDate: endDate,
      dailyPage: 1, appliedRangeLabel: `${startDate} 至 ${endDate}`, errorMessage: '', requestId: '',
    })
    await this.loadAll()
  },

  async onDailyPrevious() {
    if (this.data.dailyPage <= 1 || this.data.loading) return
    await this.loadDailyPage(this.data.dailyPage - 1)
  },

  async onDailyNext() {
    if (!this.data.dailyHasNext || this.data.loading) return
    await this.loadDailyPage(this.data.dailyPage + 1)
  },

  async loadDailyPage(page: number) {
    const generation = ++this._generation
    this._snapshot = null
    this._displaySnapshot = null
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      return
    }
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    if (this.syncCreatorScope()) { await this.loadAll(); return }
    let current = pageCurrent
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      if (this.syncCreatorScope()) { await this.loadAll(); return }
      current = this.creatorGuard(pageCurrent)
      const dailyQuery = withPage(this.currentQuery(), page)
      const snapshot = captureTabSnapshot(runtime.session, this.snapshotQuery(page))
      const daily = await runtime.statistics.daily(cloneQuery(dailyQuery))
      if (!current()) return
      this.setData({
        daily: daily.items,
        dailyPage: daily.page,
        dailyPageSize: daily.pageSize,
        dailyTotalDays: daily.totalDays,
        dailyTotalPages: daily.totalPages,
        dailyHasNext: daily.hasNext,
        loadState: this.data.summary?.entryCount === 0 ? 'empty' : 'ready',
      })
      this._displaySnapshot = snapshot
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ loadState: 'error', errorMessage: view.message, requestId: view.requestId })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  async onDirectionChange(event: WechatMiniprogram.PickerChange) {
    const needsFullLoad = this._fullLoadGeneration !== 0 || this.data.summary === null
    const directionIndex = Math.min(2, Math.max(0, Number(event.detail.value)))
    this.setData({ directionIndex, entryType: directionIndex === 2 ? 'ALL' : directionIndex === 1 ? 'INCOME' : 'EXPENSE' })
    if (needsFullLoad) await this.loadAll()
    else await this.loadRankings()
  },

  currentQuery(): string | StatisticsQuery {
    const creator = getRuntime().session.getUser()?.role === 'OWNER' && this.data.createdBy
      ? { createdBy: this.data.createdBy } : {}
    if (this.data.appliedScope === 'ALL') return { rangeType: 'ALL', ...creator }
    if (this.data.appliedScope === 'RANGE') {
      return { startDate: this.data.appliedStartDate, endDate: this.data.appliedEndDate, ...creator }
    }
    return creator.createdBy ? { month: this.data.month, ...creator } : this.data.month
  },
})
