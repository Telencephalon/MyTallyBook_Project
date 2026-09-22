import type { DailyStatisticsItem } from '../types/statistics'

export interface TrendPoint { date: string; x: number; incomeY: number | null; expenseY: number | null }
export interface TrendSeries {
  segments: { x: number; y: number; width: number; angle: number }[]
  dots: { x: number; y: number }[]
}

export function buildTrendSeries(points: TrendPoint[], key: 'incomeY' | 'expenseY'): TrendSeries {
  const segments: TrendSeries['segments'] = []
  const dots: TrendSeries['dots'] = []
  let previous: { x: number; y: number } | null = null
  points.forEach(point => {
    const y = point[key]
    if (y === null) { previous = null; return }
    const current = { x: point.x, y }
    dots.push(current)
    if (previous) {
      const dx = current.x - previous.x, dy = current.y - previous.y
      segments.push({ ...previous, width: Math.hypot(dx, dy), angle: Math.atan2(dy, dx) * 180 / Math.PI })
    }
    previous = current
  })
  return { segments, dots }
}
export interface TrendChart {
  left: number; right: number; top: number; bottom: number; maximum: number
  points: TrendPoint[]
  ticks: { y: number; label: string }[]
  xTicks: { x: number; label: string }[]
}

function amount(value: string): number | null {
  if (!value.trim()) return null
  const result = Number(value)
  return Number.isFinite(result) && result >= 0 ? result : null
}

function axisLabel(value: number): string {
  if (value >= 100000000) return `${Number((value / 100000000).toFixed(1))}亿`
  if (value >= 10000) return `${Number((value / 10000).toFixed(1))}万`
  return String(Number(value.toFixed(3)))
}

export function buildTrendChart(items: DailyStatisticsItem[], width: number, height: number): TrendChart {
  const left = 48, right = Math.max(left + 1, width - 20), top = 16, bottom = Math.max(top + 1, height - 28)
  const sorted = items.filter(item => Number.isFinite(Date.parse(item.date))).slice().sort((a, b) => a.date.localeCompare(b.date))
  const peak = sorted.reduce((max, item) => Math.max(max, amount(item.income) || 0, amount(item.expense) || 0), 0)
  const unit = peak > 0 ? Math.pow(10, Math.floor(Math.log10(peak))) : 1
  const maximum = peak > 0 ? Math.ceil(peak / unit) * unit : 1
  const first = sorted.length ? Date.parse(sorted[0].date) : 0
  const span = sorted.length ? Date.parse(sorted[sorted.length - 1].date) - first : 0
  const projectY = (value: string): number | null => {
    const number = amount(value)
    return number === null ? null : bottom - number / maximum * (bottom - top)
  }
  const points = sorted.map(item => ({
    date: item.date,
    x: span ? left + (Date.parse(item.date) - first) / span * (right - left) : (left + right) / 2,
    incomeY: projectY(item.income), expenseY: projectY(item.expense),
  }))
  const ticks = Array.from({ length: 5 }, (_, i) => ({ y: bottom - i / 4 * (bottom - top), label: axisLabel(maximum * i / 4) }))
  const count = Math.min(4, points.length)
  const xTicks = Array.from({ length: count }, (_, i) => {
    const point = points[count === 1 ? 0 : Math.round(i / (count - 1) * (points.length - 1))]
    return { x: point.x, label: point.date.slice(5) }
  })
  return { left, right, top, bottom, maximum, points, ticks, xTicks }
}
