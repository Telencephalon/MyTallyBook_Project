import { afterEach, describe, expect, it, vi } from 'vitest'

import type { Ledger, UserProfile } from '../miniprogram/types/api'

type PageShape = Record<string, any> & { data: Record<string, any>; setData(update: object): void }

const owner: UserProfile = {
  userId: 1,
  nickname: '微信用户',
  avatarUrl: null,
  ledgerId: 1,
  memberId: 1,
  role: 'OWNER',
  displayName: '家庭管理员',
}
const ledger: Ledger = {
  id: 1,
  name: '家庭账本',
  currency: 'CNY',
  timezone: 'Asia/Shanghai',
  maxMembers: 10,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((accept, decline) => { resolve = accept; reject = decline })
  return { promise, resolve, reject }
}

function runtimeFor(user: UserProfile | null = owner, currentLedger: Ledger | null = ledger) {
  let token: string | null = 'session-token'
  let revision = 1
  return {
    session: {
      getToken: () => token,
      getRevision: () => revision,
      getUser: () => user,
      getLedger: () => currentLedger,
    },
    flow: {
      refreshContext: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn().mockImplementation(async () => {
        token = null
        revision += 1
        return 'CLEARED'
      }),
    },
    markCleared() { token = null; revision += 1 },
  }
}

async function loadSettings(runtime: ReturnType<typeof runtimeFor>) {
  let definition: PageShape | undefined
  const navigateTo = vi.fn()
  const reLaunch = vi.fn()
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { navigateTo, reLaunch })
  vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => runtime }))
  await import('../miniprogram/pages/settings/index')
  const page = {
    ...definition!,
    data: structuredClone(definition!.data),
    setData(this: PageShape, update: object) { Object.assign(this.data, update) },
  } as PageShape
  return { page, navigateTo, reLaunch }
}

afterEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('settings page', () => {
  it('loads current identity and exposes the approved routes', async () => {
    const runtime = runtimeFor()
    const { page, navigateTo } = await loadSettings(runtime)

    await page.onShow()
    expect(runtime.flow.refreshContext).toHaveBeenCalledTimes(1)
    expect(page.data).toMatchObject({
      nickname: '家庭管理员',
      roleLabel: '所有者',
      ledgerName: '家庭账本',
      currency: 'CNY',
      timezone: 'Asia/Shanghai',
      maxMembers: 10,
      version: '1.0.0',
      canManageInvites: true,
      loading: false,
    })

    const routes = [
      ['openProfile', '/pages/profile/index'],
      ['openMembers', '/pages/member-list/index'],
      ['openInvites', '/pages/invite-create/index'],
      ['openCategories', '/pages/category-list/index'],
      ['openAccounts', '/pages/account-list/index'],
      ['openAbout', '/pages/about/index'],
      ['openPrivacy', '/pages/privacy/index'],
    ] as const
    for (const [handler, url] of routes) {
      page[handler]()
      expect(navigateTo).toHaveBeenLastCalledWith({ url })
    }
  })

  it('tolerates an empty session and does not permit member or invite navigation', async () => {
    const runtime = runtimeFor(null, null)
    const { page, navigateTo } = await loadSettings(runtime)
    await page.onShow()

    expect(page.data).toMatchObject({ nickname: '', roleLabel: '', ledgerName: '', canManageInvites: false })
    page.openMembers()
    page.openInvites()
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('allows members to view the member list but keeps invite management OWNER/ADMIN-only', async () => {
    const runtime = runtimeFor({ ...owner, role: 'MEMBER' })
    const { page, navigateTo } = await loadSettings(runtime)
    await page.onShow()

    page.openMembers()
    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/member-list/index' })
    navigateTo.mockClear()
    page.openInvites()
    expect(navigateTo).not.toHaveBeenCalled()
  })

  it('guards duplicate logout taps and preserves the session after a normalized network error', async () => {
    const { AppError } = await import('../miniprogram/types/error')
    const runtime = runtimeFor()
    const pending = deferred<'CLEARED'>()
    runtime.flow.logout.mockReturnValueOnce(pending.promise)
    const { page, reLaunch } = await loadSettings(runtime)

    const first = page.logout()
    await page.logout()
    expect(runtime.flow.logout).toHaveBeenCalledTimes(1)
    expect(page.data.loggingOut).toBe(true)

    pending.reject(new AppError('NETWORK', 'NETWORK_ERROR', '网络连接失败'))
    await first
    expect(page.data.loggingOut).toBe(false)
    expect(page.data.errorMessage).toBe('网络连接失败')
    expect(runtime.session.getToken()).toBe('session-token')
    expect(reLaunch).not.toHaveBeenCalled()
  })

  it('keeps a logout lock through hide/show until the original operation settles', async () => {
    const runtime = runtimeFor()
    const pending = deferred<'CLEARED'>()
    runtime.flow.logout.mockReturnValueOnce(pending.promise)
    const { page, reLaunch } = await loadSettings(runtime)

    const first = page.logout()
    page.onHide()
    await page.onShow()
    await page.logout()

    expect(runtime.flow.logout).toHaveBeenCalledTimes(1)
    expect(page.data.loggingOut).toBe(true)
    pending.resolve('CLEARED')
    await first

    expect(page.data.loggingOut).toBe(false)
    expect(reLaunch).not.toHaveBeenCalled()
  })

  it('does not duplicate the runtime 401 relaunch for ALREADY_HANDLED', async () => {
    const runtime = runtimeFor()
    runtime.flow.logout.mockImplementationOnce(async () => {
      runtime.markCleared()
      return 'ALREADY_HANDLED'
    })
    const { page, reLaunch } = await loadSettings(runtime)

    await page.logout()

    expect(reLaunch).not.toHaveBeenCalled()
    expect(page.data.errorMessage).toBe('')
    expect(page.data.loggingOut).toBe(false)
  })

  it('relaunches login exactly once after a current successful logout', async () => {
    const runtime = runtimeFor()
    const { page, reLaunch } = await loadSettings(runtime)

    await page.logout()

    expect(reLaunch).toHaveBeenCalledExactlyOnceWith({ url: '/pages/login/index' })
  })
})
