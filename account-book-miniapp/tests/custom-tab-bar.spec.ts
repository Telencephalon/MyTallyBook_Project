import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => { vi.resetModules(); vi.unstubAllGlobals() })

it('syncs the current tab on every show and switches only to registered routes', async () => {
  let definition: any
  let route = 'pages/settings/index'
  vi.stubGlobal('Component', (value: any) => { definition = value })
  vi.stubGlobal('getCurrentPages', () => [{ route }])
  vi.stubGlobal('wx', { switchTab: vi.fn() })
  await import('../miniprogram/custom-tab-bar/index')
  const bar = { ...definition.methods, data: structuredClone(definition.data),
    setData(update: object) { Object.assign(this.data, update) } }
  definition.pageLifetimes.show.call(bar)
  expect(bar.data.selected).toBe(3)
  bar.switchTab({ currentTarget: { dataset: { index: 1 } } })
  expect(wx.switchTab).toHaveBeenCalledWith(expect.objectContaining({ url: '/pages/entry-list/index' }))
  route = 'pages/entry-list/index'
  definition.pageLifetimes.show.call(bar)
  expect(bar.data.selected).toBe(1)
  vi.mocked(wx.switchTab).mockClear()
  bar.switchTab({ currentTarget: { dataset: { index: 1 } } })
  bar.switchTab({ currentTarget: { dataset: { index: 9 } } })
  expect(wx.switchTab).not.toHaveBeenCalled()
})
