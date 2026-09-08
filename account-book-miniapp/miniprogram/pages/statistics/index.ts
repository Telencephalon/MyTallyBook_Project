import { getRuntime } from '../../runtime'
import type { EntryType } from '../../types/catalog'
import type { AccountStatisticsItem, DailyStatisticsItem, MonthlySummary, RankingItem } from '../../types/statistics'
import { shanghaiToday } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

type LoadState = 'loading' | 'ready' | 'empty' | 'error'
type RankingView = RankingItem & { width: number }

function currentMonth(): string {
  return shanghaiToday().slice(0, 7)
}

function rankingViews(items: RankingItem[]): RankingView[] {
  return items.map(item => {
    const percentage = Number(item.percentage)
    return { ...item, width: Number.isFinite(percentage) ? Math.max(0, Math.min(100, percentage)) : 0 }
  })
}

Page({
  data: {
    month: currentMonth(),
    entryType: 'EXPENSE' as EntryType,
    directionIndex: 0,
    directionLabels: ['支出', '收入'],
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
  _active: true,
  _generation: 0,
  _fullLoadGeneration: 0,

  async onShow() {
    this._active = true
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
    const runtime = getRuntime()
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
        runtime.statistics.summary(this.data.month),
        runtime.statistics.daily(this.data.month),
        runtime.statistics.categories(this.data.month, this.data.entryType),
        runtime.statistics.accounts(this.data.month),
        runtime.statistics.members(this.data.month, this.data.entryType),
      ])
      if (!current()) return
      this.setData({
        month: summary.month,
        summary,
        daily: daily.items,
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
    const runtime = getRuntime()
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const [categories, members] = await Promise.all([
        runtime.statistics.categories(this.data.month, this.data.entryType),
        runtime.statistics.members(this.data.month, this.data.entryType),
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
    this.setData({ month: String(event.detail.value) })
    await this.loadAll()
  },

  async onDirectionChange(event: WechatMiniprogram.PickerChange) {
    const needsFullLoad = this._fullLoadGeneration !== 0 || this.data.summary === null
    const directionIndex = Number(event.detail.value) === 1 ? 1 : 0
    this.setData({ directionIndex, entryType: directionIndex === 1 ? 'INCOME' : 'EXPENSE' })
    if (needsFullLoad) await this.loadAll()
    else await this.loadRankings()
  },
})
