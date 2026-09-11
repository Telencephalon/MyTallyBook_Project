import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { afterEach, describe, expect, it, vi } from 'vitest'

type PageShape = Record<string, any> & { data: Record<string, any> }

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const miniprogram = join(root, 'miniprogram')

async function loadStaticPage(name: 'about' | 'privacy') {
  let definition: PageShape | undefined
  const request = vi.fn()
  vi.stubGlobal('Page', (value: PageShape) => { definition = value })
  vi.stubGlobal('wx', { request, setClipboardData: vi.fn() })
  if (name === 'about') await import('../miniprogram/pages/about/index')
  else await import('../miniprogram/pages/privacy/index')
  return { page: definition!, request }
}

afterEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('about and privacy public pages', () => {
  it('publishes stable non-secret app metadata and performs no load-time request', async () => {
    const { page, request } = await loadStaticPage('about')
    expect(page.data).toMatchObject({
      appName: '随手账',
      version: '1.0.0',
      operatorText: '待配置',
      contactText: '待配置',
      filingText: '备案中',
      filingQueryUrl: 'https://beian.miit.gov.cn',
    })
    expect(request).not.toHaveBeenCalled()
    expect(JSON.stringify(page.data)).not.toMatch(/token|password|secret|appsecret|数据库密码/i)
  })

  it('keeps privacy static and does not request network data on load', async () => {
    const { request } = await loadStaticPage('privacy')
    expect(request).not.toHaveBeenCalled()
  })

  it('describes every actual data category and the non-deletion effect of logout', () => {
    const privacyPath = join(miniprogram, 'pages', 'privacy', 'index.wxml')
    expect(existsSync(privacyPath)).toBe(true)
    const copy = readFileSync(privacyPath, 'utf8')

    for (const category of ['微信身份标识', '会话', '共享账本', '成员', '角色', '记账记录', '审计', '邀请', '请求编号']) {
      expect(copy).toContain(category)
    }
    expect(copy).toMatch(/退出登录[^。]*不会删除[^。]*(共享账本|历史记录)/)
    expect(copy).not.toMatch(/读取剪贴板|通讯录|位置|相机|摄像头|麦克风|手机号|相册|照片/)
  })

  it('registers public routes and keeps login links independent from an active login completion', () => {
    const app = JSON.parse(readFileSync(join(miniprogram, 'app.json'), 'utf8')) as { pages: string[] }
    expect(app.pages).toContain('pages/about/index')
    expect(app.pages).toContain('pages/privacy/index')

    const login = readFileSync(join(miniprogram, 'pages', 'login', 'index.wxml'), 'utf8')
    expect(login).toContain('bindtap="openAbout"')
    expect(login).toContain('bindtap="openPrivacy"')
  })

  it.each([
    ['openAbout', '/pages/about/index', 'success'],
    ['openAbout', '/pages/about/index', 'failure'],
    ['openPrivacy', '/pages/privacy/index', 'success'],
    ['openPrivacy', '/pages/privacy/index', 'failure'],
  ] as const)('%s cancels a pending login %s continuation', async (handler, url, outcome) => {
    let definition: (PageShape & { setData(update: object): void }) | undefined
    let finishLogin!: (value: { destination: 'HOME' }) => void
    let failLogin!: (reason: unknown) => void
    const start = vi.fn().mockReturnValue(new Promise((resolve, reject) => {
      finishLogin = resolve
      failLogin = reject
    }))
    const navigateTo = vi.fn()
    const reLaunch = vi.fn()
    vi.stubGlobal('Page', (value: typeof definition) => { definition = value })
    vi.stubGlobal('wx', { navigateTo, redirectTo: vi.fn(), reLaunch })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => ({ flow: { start } }) }))
    await import('../miniprogram/pages/login/index')
    const page = {
      ...definition!,
      data: structuredClone(definition!.data),
      setData(update: object) { Object.assign(this.data, update) },
    } as PageShape

    page.onLoad()
    page[handler]()
    if (outcome === 'success') finishLogin({ destination: 'HOME' })
    else failLogin(new Error('stale login failure'))
    await Promise.resolve()
    await Promise.resolve()

    expect(navigateTo).toHaveBeenCalledWith(expect.objectContaining({ url }))
    expect(reLaunch).not.toHaveBeenCalled()
    expect(page.data.errorMessage).toBe('')
  })

  it('reconciles busy state after returning from a public page and ignores stale login completion', async () => {
    let definition: PageShape | undefined
    const attempts: Array<{ resolve(value: { destination: 'HOME' }): void }> = []
    const start = vi.fn().mockImplementation(() => new Promise(resolve => { attempts.push({ resolve }) }))
    const reLaunch = vi.fn()
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { navigateTo: vi.fn(), redirectTo: vi.fn(), reLaunch })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => ({ flow: { start } }) }))
    await import('../miniprogram/pages/login/index')
    const page = {
      ...definition!,
      data: structuredClone(definition!.data),
      setData(update: object) { Object.assign(this.data, update) },
    } as PageShape

    page.onLoad()
    await Promise.resolve()
    page.onHide()
    page.onShow()
    expect(start).toHaveBeenCalledTimes(2)
    expect(page.data.busy).toBe(true)

    attempts[1].resolve({ destination: 'HOME' })
    await Promise.resolve()
    await Promise.resolve()
    expect(reLaunch).toHaveBeenCalledTimes(1)

    attempts[0].resolve({ destination: 'HOME' })
    await Promise.resolve()
    await Promise.resolve()
    expect(reLaunch).toHaveBeenCalledTimes(1)
  })

  it('restores login after public navigation fails and rejects duplicate public taps', async () => {
    let definition: PageShape | undefined
    const start = vi.fn().mockResolvedValue({ destination: 'INVITE_REQUIRED' as const })
    const navigateTo = vi.fn()
    vi.stubGlobal('Page', (value: PageShape) => { definition = value })
    vi.stubGlobal('wx', { navigateTo, redirectTo: vi.fn(), reLaunch: vi.fn() })
    vi.doMock('../miniprogram/runtime', () => ({ getRuntime: () => ({ flow: { start } }) }))
    await import('../miniprogram/pages/login/index')
    const page = {
      ...definition!,
      data: structuredClone(definition!.data),
      setData(update: object) { Object.assign(this.data, update) },
    } as PageShape

    page.onLoad()
    await Promise.resolve()
    page.openPrivacy()
    page.openPrivacy()
    expect(navigateTo).toHaveBeenCalledTimes(1)
    const options = navigateTo.mock.calls[0][0] as { fail(reason: unknown): void }
    options.fail(new Error('navigation failed'))
    await Promise.resolve()
    await Promise.resolve()
    expect(start).toHaveBeenCalledTimes(2)
    expect(page.data.busy).toBe(false)
  })
})
