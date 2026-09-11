import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TAB_ROUTES } from '../miniprogram/utils/navigation'

type PageShape = Record<string, any> & {
  data: Record<string, any>
  setData(update: object): void
}

function copyPage(definition: PageShape): PageShape {
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(this: PageShape, update: object) {
      Object.assign(this.data, update)
    },
  }
}

afterEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('four-tab navigation contract', () => {
  it('registers exactly the approved four tab roots and keeps editor pages out', () => {
    const config = JSON.parse(readFileSync(new URL('../miniprogram/app.json', import.meta.url), 'utf8')) as {
      tabBar?: { list?: Array<{ pagePath: string; text: string }> }
    }
    expect(config.tabBar?.list).toEqual([
      { pagePath: 'pages/home/index', text: '首页' },
      { pagePath: 'pages/entry-list/index', text: '明细' },
      { pagePath: 'pages/statistics/index', text: '统计' },
      { pagePath: 'pages/settings/index', text: '我的' },
    ])
    expect(config.tabBar?.list?.map(item => `/${item.pagePath}`)).toEqual(TAB_ROUTES)
    expect(config.tabBar?.list?.map(item => item.pagePath)).not.toContain('pages/profile/index')
    expect(config.tabBar?.list?.map(item => item.pagePath)).not.toContain('pages/entry-detail/index')
  })

  it('switches known tabs and navigates stack routes while preserving query strings', async () => {
    const switchTab = vi.fn()
    const navigateTo = vi.fn()
    vi.stubGlobal('wx', { switchTab, navigateTo })
    const { navigateToPage } = await import('../miniprogram/utils/navigation')

    navigateToPage('/pages/statistics/index')
    navigateToPage('/pages/profile/index?source=settings')

    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/statistics/index' })
    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/profile/index?source=settings' })
  })

  it('executes Home.openEntries and Home.openStatistics through the native tab switch', async () => {
    let definition: PageShape | undefined
    const switchTab = vi.fn()
    const navigateTo = vi.fn()
    const runtime = {
      session: {
        getUser: () => ({ userId: 1, nickname: '用户', role: 'OWNER' }),
      },
    }
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { switchTab, navigateTo })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
    await import('../miniprogram/pages/home/index')

    const page = copyPage(definition!)
    page.openEntries()
    page.openStatistics()

    expect(switchTab).toHaveBeenNthCalledWith(1, { url: '/pages/entry-list/index' })
    expect(switchTab).toHaveBeenNthCalledWith(2, { url: '/pages/statistics/index' })
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('falls back to navigateTo for a tab route when switchTab is unavailable', async () => {
    const navigateTo = vi.fn()
    vi.stubGlobal('wx', { navigateTo })
    const { navigateToPage } = await import('../miniprogram/utils/navigation')

    navigateToPage('/pages/entry-list/index')

    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/entry-list/index' })
  })

  it('executes Profile.saveProfile and returns after a successful nickname update', async () => {
    let definition: PageShape | undefined
    const navigateBack = vi.fn()
    const oldUser = {
      userId: 1,
      nickname: '旧昵称',
      avatarUrl: null,
      ledgerId: 1,
      memberId: 1,
      role: 'OWNER',
    }
    const updatedUser = { ...oldUser, nickname: '新昵称' }
    const runtime = {
      session: { getUser: () => oldUser },
      flow: {
        captureContextGuard: () => () => true,
        updateNickname: vi.fn().mockResolvedValue(updatedUser),
      },
    }
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { navigateBack })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
    await import('../miniprogram/pages/profile/index')

    const page = copyPage(definition!)
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '新昵称' } })
    await page.saveProfile()

    expect(runtime.flow.updateNickname).toHaveBeenCalledWith('新昵称', expect.any(Function))
    expect(page.data.originalNickname).toBe('新昵称')
    expect(navigateBack).toHaveBeenCalledOnce()
  })

  it('executes authorized EntryDetail deletion and switches to the entry tab', async () => {
    let definition: PageShape | undefined
    const switchTab = vi.fn()
    const navigateTo = vi.fn()
    const entry = {
      id: 40,
      entryType: 'EXPENSE',
      amount: '3.40',
      categoryId: 7,
      categoryName: '餐饮',
      categoryStatus: 'ACTIVE',
      accountId: 8,
      accountName: '现金',
      accountStatus: 'ACTIVE',
      entryDate: '2026-09-06',
      note: '晚餐',
      createdBy: 1,
      creatorName: '用户',
      createdAt: '2026-09-06T01:00:00Z',
      updatedAt: '2026-09-06T01:00:00Z',
      version: 2,
      canEdit: true,
      canDelete: true,
      clientRequestId: '11111111-2222-4333-8444-555555555555',
    }
    const runtime = {
      session: { getRevision: () => 1 },
      flow: { refreshContext: vi.fn().mockResolvedValue(undefined) },
      entries: {
        detail: vi.fn().mockResolvedValue(entry),
        remove: vi.fn().mockResolvedValue(undefined),
      },
    }
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', {
      switchTab,
      navigateTo,
      showModal: vi.fn((options: { success(result: { confirm: boolean }): void }) => {
        options.success({ confirm: true })
      }),
    })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
    await import('../miniprogram/pages/entry-detail/index')

    const page = copyPage(definition!)
    page.onLoad({ id: '40' })
    await page.onShow()
    await page.onDelete()

    expect(runtime.entries.remove).toHaveBeenCalledWith(40, 2)
    expect(switchTab).toHaveBeenCalledTimes(1)
    expect(switchTab).toHaveBeenCalledWith({ url: '/pages/entry-list/index' })
    expect(navigateTo).not.toHaveBeenCalled()
  })
})
