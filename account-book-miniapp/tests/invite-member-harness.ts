import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, vi } from 'vitest'
import type { RawRequestOptions } from '../miniprogram/services/http'
import type { UserProfile } from '../miniprogram/types/api'

export const inviteToken = 'Ab_'.repeat(14) + 'Z'
export const ledger = { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 }
export const owner: UserProfile = {
  userId: 1, memberId: 11, nickname: '所有者昵称', displayName: null,
  avatarUrl: null, ledgerId: 1, role: 'OWNER',
}
export const other = { memberId: 22, userId: 2, nickname: '成员昵称', displayName: null, role: 'MEMBER', joinedAt: '2026-09-01T00:00:00Z' }
// Page/wx are platform boundaries. All runtime, HTTP, API, flow and session logic remains real.
export type TestPage = { data: Record<string, any>; [method: string]: any }

export async function harness() {
  vi.resetModules()
  const storage = new Map<string, unknown>()
  const requests: RawRequestOptions[] = []
  let currentUser = { ...owner }
  let members = [{ ...other }]
  let handler: ((request: RawRequestOptions) => boolean) | undefined
  let captured: TestPage
  const reply = (r: RawRequestOptions, data: unknown, statusCode = 200, code = 'OK') =>
    r.success({ statusCode, header: {}, data: { code, message: code === 'OK' ? 'success' : '安全错误', data, requestId: 'test-request', timestamp: '2026-09-05T00:00:00Z' } })
  const platform = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    getStorageSync: (key: string) => storage.get(key),
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    login: vi.fn((options: { success(value: { code: string }): void }) => options.success({ code: 'fresh-code-' + platform.login.mock.calls.length })),
    request: (r: RawRequestOptions) => {
      requests.push(r)
      if (handler?.(r)) return
      if (r.url.endsWith('/users/me')) return reply(r, currentUser)
      if (r.url.endsWith('/ledger')) return reply(r, ledger)
      if (r.url.endsWith('/members') && r.method === 'GET') return reply(r, { items: members, activeCount: 2, maxMembers: 10, ownerUserId: 1 })
      if (r.url.includes('/invites?') && r.method === 'GET') return reply(r, { items: [], page: 1, pageSize: 20, total: 0 })
      throw new Error('Unexpected request: ' + r.method + ' ' + r.url)
    },
    navigateTo: vi.fn(), navigateBack: vi.fn(), reLaunch: vi.fn(), redirectTo: vi.fn(),
    setClipboardData: vi.fn(), hideShareMenu: vi.fn(), showShareMenu: vi.fn(),
    showModal: vi.fn((options: { success(value: { confirm: boolean; cancel: boolean }): void }) => options.success({ confirm: true, cancel: false })),
  }
  vi.stubGlobal('wx', platform)
  vi.stubGlobal('Page', (definition: TestPage) => { captured = definition })
  const { getRuntime } = await import('../miniprogram/runtime')
  const runtime = getRuntime()
  runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'existing-test-session', expiresAt: '2099-01-01T00:00:00Z' })
  runtime.session.setContext(currentUser, ledger)
  return {
    runtime, storage, requests, platform, reply,
    setUser(user: UserProfile) { currentUser = user; runtime.session.setContext(user, ledger) },
    serverUser(user: UserProfile) { currentUser = user },
    setMembers(value: typeof members) { members = value },
    respond(value: typeof handler) { handler = value },
    async page(name: string): Promise<TestPage> {
      expect(existsSync(resolve('miniprogram/pages', name, 'index.ts')), 'page must exist before loading').toBe(true)
      await import('../miniprogram/pages/' + name + '/index.ts')
      return { ...captured!, data: structuredClone(captured!.data), setData(this: TestPage, update: object) { Object.assign(this.data, update) } }
    },
  }
}
