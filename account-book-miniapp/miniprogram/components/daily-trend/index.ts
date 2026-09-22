import { buildTrendChart, buildTrendSeries } from '../../utils/trend-chart'
import type { TrendChart, TrendSeries } from '../../utils/trend-chart'
import type { DailyStatisticsItem } from '../../types/statistics'

const drawStates = new WeakMap<object, { version: number; visible: boolean }>()

Component({
  properties: { items: { type: Array, value: [] as DailyStatisticsItem[] } },
  data: {
    drawFailed: false,
    chart: null as TrendChart | null,
    series: [] as (TrendSeries & { name: string })[],
  },
  observers: { items() { this.draw() } },
  lifetimes: {
    ready() { drawStates.set(this, { version: 0, visible: true }); this.draw() },
    detached() { drawStates.delete(this) },
  },
  pageLifetimes: {
    resize() { this.draw() },
    show() {
      const state = drawStates.get(this)
      if (state) state.visible = true
      this.draw()
    },
    hide() {
      const state = drawStates.get(this)
      if (state) { state.visible = false; state.version += 1 }
    },
  },
  methods: {
    draw() {
      const state = drawStates.get(this)
      if (!state?.visible) return
      const version = ++state.version
      const current = () => drawStates.get(this) === state && state.visible && version === state.version
      // Measure after layout; ordinary views keep the plot inside its card.
      wx.nextTick(() => {
        if (!current()) return
        this.createSelectorQuery().select('#trend-plot').boundingClientRect().exec(results => {
          if (!current()) return
          const result = results[0] as { width: number; height: number } | undefined
          if (!result?.width || !result.height) {
            this.setData({ chart: null, series: [], drawFailed: true })
            return
          }
          const chart = buildTrendChart(this.data.items as DailyStatisticsItem[], result.width, result.height)
          this.setData({ chart, drawFailed: false, series: [
            { name: 'income', ...buildTrendSeries(chart.points, 'incomeY') },
            { name: 'expense', ...buildTrendSeries(chart.points, 'expenseY') },
          ] })
        })
      })
    },
  },
})
