import { buildTrendChart } from '../../utils/trend-chart'
import type { DailyStatisticsItem } from '../../types/statistics'

const drawStates = new WeakMap<object, { version: number }>()

Component({
  properties: { items: { type: Array, value: [] as DailyStatisticsItem[] } },
  data: { drawFailed: false },
  observers: { items() { this.draw() } },
  lifetimes: {
    ready() { drawStates.set(this, { version: 0 }); this.draw() },
    detached() { drawStates.delete(this) },
  },
  pageLifetimes: { resize() { this.draw() }, show() { this.draw() } },
  methods: {
    draw() {
      const state = drawStates.get(this)
      if (!state) return
      const version = ++state.version
      this.createSelectorQuery().select('#trend-canvas').fields({ node: true, size: true }).exec(results => {
        if (drawStates.get(this) !== state || version !== state.version) return
        try {
          const result = results[0] as { node?: WechatMiniprogram.Canvas; width: number; height: number } | undefined
          if (!result?.node || !result.width || !result.height) throw new Error('Canvas unavailable')
          const { node: canvas, width, height } = result
          const ratio = wx.getWindowInfo().pixelRatio || 1
          canvas.width = Math.round(width * ratio)
          canvas.height = Math.round(height * ratio)
          const context = canvas.getContext('2d')
          context.scale(ratio, ratio)
          context.clearRect(0, 0, width, height)
          const chart = buildTrendChart(this.data.items as DailyStatisticsItem[], width, height)
          context.font = '10px sans-serif'
          context.lineWidth = 1
          context.textAlign = 'right'
          context.textBaseline = 'middle'
          chart.ticks.forEach(tick => {
            context.fillStyle = '#7a8495'
            context.fillText(tick.label, chart.left - 8, tick.y)
            context.strokeStyle = '#edf0f5'
            context.beginPath(); context.moveTo(chart.left, tick.y); context.lineTo(chart.right, tick.y); context.stroke()
          })
          context.textAlign = 'center'
          chart.xTicks.forEach(tick => context.fillText(tick.label, tick.x, chart.bottom + 18))
          ;(['incomeY', 'expenseY'] as const).forEach((key, index) => {
            const color = index === 0 ? '#3e7dd6' : '#b85654'
            context.strokeStyle = color; context.fillStyle = color; context.lineWidth = 2
            context.beginPath()
            let connected = false
            chart.points.forEach(point => {
              const y = point[key]
              if (y === null) { connected = false; return }
              if (connected) context.lineTo(point.x, y)
              else context.moveTo(point.x, y)
              connected = true
            })
            context.stroke()
            chart.points.forEach(point => {
              const y = point[key]
              if (y === null) return
              context.beginPath(); context.arc(point.x, y, 2.5, 0, Math.PI * 2); context.fill()
            })
          })
          if (this.data.drawFailed) this.setData({ drawFailed: false })
        } catch {
          this.setData({ drawFailed: true })
        }
      })
    },
  },
})
