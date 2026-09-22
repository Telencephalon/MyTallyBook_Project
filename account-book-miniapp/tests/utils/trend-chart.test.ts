import { describe, expect, it } from 'vitest'
import { buildTrendChart, buildTrendSeries } from '../../miniprogram/utils/trend-chart'

const day = (date: string, income: string, expense = '0.00') => ({ date, income, expense, net: '0.00', entryCount: 1 })

describe('daily trend chart projection', () => {
  it('handles empty data without inventing dates or values', () => {
    const chart = buildTrendChart([], 320, 200)
    expect(chart.points).toEqual([])
    expect(chart.xTicks).toEqual([])
  })
  it('plots both directions on the same zero-based scale', () => {
    const chart = buildTrendChart([day('2026-09-20', '1000', '500'), day('2026-09-21', '0', '250')], 320, 200)
    expect(chart.maximum).toBe(1000)
    expect(chart.points[0].incomeY).toBe(chart.top)
    expect(chart.points[0].expenseY).toBe((chart.top + chart.bottom) / 2)
    expect(chart.points[1].incomeY).toBe(chart.bottom)
  })
  it('keeps zero and one-day series finite and places the single day centrally', () => {
    const chart = buildTrendChart([day('2026-09-22', '0')], 280, 180)
    expect(chart.points[0].x).toBe((chart.left + chart.right) / 2)
    expect(chart.points[0].incomeY).toBe(chart.bottom)
    expect(chart.points[0].expenseY).toBe(chart.bottom)
    expect(chart.ticks.every(tick => Number.isFinite(tick.y))).toBe(true)
  })
  it('uses elapsed dates for horizontal spacing without mutating input order', () => {
    const items = [day('2026-09-04', '3'), day('2026-09-01', '1'), day('2026-09-02', '2')]
    const chart = buildTrendChart(items, 320, 200)
    expect(chart.points.map(point => point.date)).toEqual(['2026-09-01','2026-09-02','2026-09-04'])
    expect(chart.points[1].x - chart.left).toBeCloseTo((chart.right - chart.left) / 3)
    expect(items[0].date).toBe('2026-09-04')
  })
  it('limits month labels and includes the first and last date', () => {
    const items = Array.from({length:31}, (_, i) => day(`2026-08-${String(i+1).padStart(2,'0')}`, '0.01'))
    const chart = buildTrendChart(items, 280, 180)
    expect(chart.xTicks.length).toBeLessThanOrEqual(4)
    expect(chart.xTicks[0].label).toBe('08-01')
    expect(chart.xTicks[chart.xTicks.length - 1]?.label).toBe('08-31')
    expect(chart.maximum).toBeGreaterThanOrEqual(0.01)
  })
  it('does not plot malformed values as a real zero', () => {
    const chart = buildTrendChart([day('2026-09-22','invalid','12')],320,200)
    expect(chart.points[0].incomeY).toBeNull()
    expect(chart.points[0].expenseY).not.toBeNull()
  })
})

describe('ordinary-view trend series', () => {
  it('projects a rising line with the correct length and rotation', () => {
    const series = buildTrendSeries([
      { date: '2026-09-01', x: 10, incomeY: 50, expenseY: 0 },
      { date: '2026-09-02', x: 40, incomeY: 10, expenseY: 0 },
    ], 'incomeY')
    expect(series.segments).toHaveLength(1)
    expect(series.segments[0]).toMatchObject({ x: 10, y: 50, width: 50 })
    expect(series.segments[0].angle).toBeCloseTo(-53.1301, 3)
    expect(series.dots).toEqual([{ x: 10, y: 50 }, { x: 40, y: 10 }])
  })

  it('breaks at missing amounts and preserves the zero baseline', () => {
    const series = buildTrendSeries([
      { date: '2026-09-01', x: 10, incomeY: 10, expenseY: 100 },
      { date: '2026-09-02', x: 20, incomeY: 20, expenseY: null },
      { date: '2026-09-03', x: 30, incomeY: 30, expenseY: 100 },
      { date: '2026-09-04', x: 40, incomeY: 40, expenseY: 100 },
    ], 'expenseY')
    expect(series.segments).toEqual([{ x: 30, y: 100, width: 10, angle: 0 }])
    expect(series.dots).toHaveLength(3)
  })
})
