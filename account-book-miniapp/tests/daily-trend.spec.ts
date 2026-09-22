import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => { vi.resetModules(); vi.unstubAllGlobals() })

async function harness() {
  let definition: any
  const ticks: (() => void)[] = []
  const queries: ((results: unknown[]) => void)[] = []
  vi.stubGlobal('Component', (value: any) => { definition = value })
  vi.stubGlobal('wx', { nextTick: (callback: () => void) => ticks.push(callback) })
  await import('../miniprogram/components/daily-trend/index')
  const instance = {
    ...definition.methods,
    data: { ...structuredClone(definition.data), items: [{ date: '2026-09-22', income: '1500', expense: '0', net: '1500', entryCount: 1 }] },
    setData: vi.fn(function (this: any, update: object) { Object.assign(this.data, update) }),
    createSelectorQuery() {
      const query = { select: () => query, boundingClientRect: () => query, exec: (callback: (results: unknown[]) => void) => queries.push(callback) }
      return query
    },
  }
  return { definition, instance, ticks, queries }
}

it('measures after layout and plots relative to its container, ignoring page offsets', async () => {
  const { definition, instance, ticks, queries } = await harness()
  definition.lifetimes.ready.call(instance)
  expect(queries).toHaveLength(0)
  ticks.shift()!()
  queries.shift()!([{ width: 300, height: 200, top: 800, left: 20 }])
  expect(instance.data.chart).toMatchObject({ left: 48, right: 280, top: 16, bottom: 172 })
  expect(instance.data.series[0].dots).toEqual([{ x: 164, y: 55 }])
})

it('ignores old measurements after hiding and redraws on return with current dimensions', async () => {
  const { definition, instance, ticks, queries } = await harness()
  definition.lifetimes.ready.call(instance); ticks.shift()!()
  definition.pageLifetimes.hide.call(instance)
  queries.shift()!([{ width: 300, height: 200 }])
  expect(instance.setData).not.toHaveBeenCalled()
  definition.pageLifetimes.show.call(instance); ticks.shift()!()
  queries.shift()!([{ width: 260, height: 180 }])
  expect(instance.data.chart).toMatchObject({ right: 240, bottom: 152 })
})

it('ignores stale resize results and callbacks from a detached component', async () => {
  const { definition, instance, ticks, queries } = await harness()
  definition.lifetimes.ready.call(instance); ticks.shift()!()
  definition.pageLifetimes.resize.call(instance); ticks.shift()!()
  queries[1]([{ width: 260, height: 180 }])
  queries[0]([{ width: 300, height: 200 }])
  expect(instance.data.chart.right).toBe(240)
  definition.pageLifetimes.resize.call(instance); ticks.shift()!()
  definition.lifetimes.detached.call(instance)
  queries[2]([{ width: 400, height: 200 }])
  expect(instance.setData).toHaveBeenCalledTimes(1)
})
