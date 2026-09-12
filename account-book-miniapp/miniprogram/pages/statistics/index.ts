import { getRuntime } from '../../runtime'
import type { AccountStatisticsItem, DailyStatisticsItem, MonthlySummary, RankingItem, RankingDirection, StatisticsQuery } from '../../types/statistics'
import { shanghaiToday } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

type LoadState = 'loading' | 'ready' | 'empty' | 'error'
type RankingView = RankingItem & { width: number }
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
  data: {
    month: currentMonth(),
    scopeIndex: 0,
    scopeLabels: ['本月', '全部', '自定义'],
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
    entryType: 'ALL' as RankingDirection,
    directionIndex: 2,
    directionLabels: ['支出', '收入', '全部'],
    summary: null as MonthlySummary | null,
    daily: [] as DailyStatisticsItem[],
    categoryTotal: '0.00',
    categoryItems: [] as RankingView[],
    accountItems: [] as AccountStatisticsItem[],
    memberTotal: '0.00',
    memberItems: [] as RankingView[],
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

  async onShow() {
    this.getTabBar?.()?.setData({ selected: 2 })

    this._active = true
    if (!this._hasExplicitScope && this.data.month === this._defaultMonthAtDefinition) {
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
    await this.loadAll()
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

  async loadAll() {
    const generation = ++this._generation
    this._fullLoadGeneration = generation
    const baseQuery = this.currentQuery()
    const dailyQuery = withPage(baseQuery, this.data.dailyPage)
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      this._fullLoadGeneration = 0
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({
      loading: true, loadState: 'loading', errorMessage: '', requestId: '',
      summary: null, daily: [], categoryTotal: '0.00', categoryItems: [],
      accountItems: [], memberTotal: '0.00', memberItems: [],
    })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const [summary, daily, categories, accounts, members] = await Promise.all([
        runtime.statistics.summary(cloneQuery(baseQuery)),
        runtime.statistics.daily(cloneQuery(dailyQuery)),
        runtime.statistics.categories(cloneQuery(baseQuery), this.data.entryType),
        runtime.statistics.accounts(cloneQuery(baseQuery)),
        runtime.statistics.members(cloneQuery(baseQuery), this.data.entryType),
      ])
      if (!current()) return
      this.setData({
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
        categoryTotal: categories.total,
        categoryItems: rankingViews(categories.items),
        accountItems: accounts.items,
        memberTotal: members.total,
        memberItems: rankingViews(members.items),
        loadState: summary.entryCount === 0 ? 'empty' : 'ready',
        errorMessage: '',
        requestId: '',
      })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({
        summary: null,
        daily: [],
        categoryTotal: '0.00',
        categoryItems: [],
        accountItems: [],
        memberTotal: '0.00',
        memberItems: [],
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
    const baseQuery = this.currentQuery()
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const [categories, members] = await Promise.all([
        runtime.statistics.categories(cloneQuery(baseQuery), this.data.entryType),
        runtime.statistics.members(cloneQuery(baseQuery), this.data.entryType),
      ])
      if (!current()) return
      this.setData({
        categoryTotal: categories.total,
        categoryItems: rankingViews(categories.items),
        memberTotal: members.total,
        memberItems: rankingViews(members.items),
        loadState: this.data.summary?.entryCount === 0 ? 'empty' : 'ready',
      })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({
        categoryTotal: '0.00', categoryItems: [], memberTotal: '0.00', memberItems: [],
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
    const baseQuery = this.currentQuery()
    const dailyQuery = withPage(baseQuery, page)
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, loadState: 'error', errorMessage: view.message, requestId: view.requestId })
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
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
    if (this.data.appliedScope === 'ALL') return { rangeType: 'ALL' }
    if (this.data.appliedScope === 'RANGE') {
      return { startDate: this.data.appliedStartDate, endDate: this.data.appliedEndDate }
    }
    return this.data.month
  },
})
