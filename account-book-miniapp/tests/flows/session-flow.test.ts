import { describe, expect, it } from 'vitest'
import {
  SessionFlow,
  type AuthGateway,
  type LedgerGateway,
  type UserGateway,
} from '../../miniprogram/flows/session-flow'
import {
  SESSION_STORAGE_KEY,
  SessionStore,
  type StorageAdapter,
} from '../../miniprogram/store/session'
import type { AuthStateData, Ledger, UserProfile } from '../../miniprogram/types/api'
import { AppError } from '../../miniprogram/types/error'

class MemoryStorage implements StorageAdapter {
  readonly values = new Map<string, unknown>()
  get(key: string): unknown { return this.values.get(key) }
  set(key: string, value: unknown): void { this.values.set(key, value) }
  remove(key: string): void { this.values.delete(key) }
}

const NOW = Date.parse('2026-08-31T00:00:00Z')
const FUTURE = '2026-09-01T00:00:00Z'

const user: UserProfile = {
  userId: 1,
  nickname: '微信用户',
  avatarUrl: null,
  ledgerId: 1,
  memberId: 1,
  role: 'OWNER',
  displayName: '微信用户',
}

const ledger: Ledger = {
  id: 1,
  name: '共享账本',
  currency: 'CNY',
  timezone: 'Asia/Shanghai',
  maxMembers: 10,
}

function authenticated(token = 'new-token'): AuthStateData {
  return { state: 'AUTHENTICATED', token, expiresAt: FUTURE }
}

function createHarness(options: {
  loginResult?: AuthStateData
  bootstrapResult?: AuthStateData
  logoutError?: AppError
  codes?: string[]
} = {}) {
  const storage = new MemoryStorage()
  const session = new SessionStore(storage, () => NOW)
  const loginCodes: string[] = []
  const bootstrapCalls: Array<{ code: string; bootstrapKey: string }> = []
  const suppliedCodes = [...(options.codes ?? ['wechat-code'])]
  let codeCalls = 0

  const auth: AuthGateway = {
    async login(code) {
      loginCodes.push(code)
      return options.loginResult ?? authenticated()
    },
    async bootstrap(code, bootstrapKey) {
      bootstrapCalls.push({ code, bootstrapKey })
      return options.bootstrapResult ?? authenticated()
    },
    async logout() {
      if (options.logoutError) throw options.logoutError
      return { state: 'LOGGED_OUT' }
    },
  }
  const users: UserGateway = {
    getMe: async () => user,
    updateNickname: async nickname => ({ ...user, nickname }),
  }
  const ledgers: LedgerGateway = { getFixedLedger: async () => ledger }
  const flow = new SessionFlow(
    session,
    auth,
    users,
    ledgers,
    async () => {
      const code = suppliedCodes[codeCalls] ?? `wechat-code-${codeCalls + 1}`
      codeCalls += 1
      return code
    },
  )

  return {
    storage,
    session,
    flow,
    loginCodes,
    bootstrapCalls,
    getCodeCalls: () => codeCalls,
  }
}

describe('SessionFlow.start', () => {
  it('restores a valid Token and loads context without obtaining a new code', async () => {
    const harness = createHarness()
    harness.storage.values.set(SESSION_STORAGE_KEY, {
      version: 1,
      token: 'persisted-token',
      expiresAt: FUTURE,
    })

    await expect(harness.flow.start()).resolves.toEqual({ destination: 'HOME' })
    expect(harness.getCodeCalls()).toBe(0)
    expect(harness.session.getUser()).toEqual(user)
    expect(harness.session.getLedger()).toEqual(ledger)
  })

  it('logs in without a Token, persists authentication, and loads context', async () => {
    const harness = createHarness({ codes: ['fresh-code'] })

    await expect(harness.flow.start()).resolves.toEqual({ destination: 'HOME' })
    expect(harness.loginCodes).toEqual(['fresh-code'])
    expect(harness.session.getToken()).toBe('new-token')
    expect(harness.session.getUser()).toEqual(user)
    expect(harness.session.getLedger()).toEqual(ledger)
  })

  it.each([
    ['NEED_BOOTSTRAP', 'BOOTSTRAP'],
    ['INVITE_REQUIRED', 'INVITE_REQUIRED'],
  ] as const)('maps %s without persisting a session', async (state, destination) => {
    const harness = createHarness({
      loginResult: { state, token: null, expiresAt: null },
    })

    await expect(harness.flow.start()).resolves.toEqual({ destination })
    expect(harness.session.getToken()).toBeNull()
    expect(harness.storage.values.has(SESSION_STORAGE_KEY)).toBe(false)
  })
})

describe('SessionFlow.bootstrap', () => {
  it('trims the key and obtains a fresh code for every submission', async () => {
    const harness = createHarness({ codes: ['code-one', 'code-two'] })

    await expect(harness.flow.bootstrap(' 12345678901234567890 '))
      .resolves.toEqual({ destination: 'HOME' })
    await expect(harness.flow.bootstrap('12345678901234567890'))
      .resolves.toEqual({ destination: 'HOME' })

    expect(harness.bootstrapCalls).toEqual([
      { code: 'code-one', bootstrapKey: '12345678901234567890' },
      { code: 'code-two', bootstrapKey: '12345678901234567890' },
    ])
  })

  it('rejects invalid length before obtaining a WeChat code', async () => {
    const harness = createHarness()

    await expect(harness.flow.bootstrap('short')).rejects.toMatchObject({
      code: 'CLIENT_VALIDATION_FAILED',
    })
    expect(harness.getCodeCalls()).toBe(0)
    expect(harness.bootstrapCalls).toEqual([])
  })

  it('rejects a non-authenticated bootstrap response without persisting it', async () => {
    const harness = createHarness({
      bootstrapResult: { state: 'NEED_BOOTSTRAP', token: null, expiresAt: null },
    })

    await expect(harness.flow.bootstrap('12345678901234567890'))
      .rejects.toMatchObject({ code: 'INVALID_AUTH_RESPONSE' })
    expect(harness.session.getToken()).toBeNull()
  })
})

describe('SessionFlow.logout', () => {
  it('clears the local session after server logout succeeds', async () => {
    const harness = createHarness()
    harness.session.saveAuthenticated(authenticated('active-token'))

    await expect(harness.flow.logout()).resolves.toBe('CLEARED')
    expect(harness.session.getToken()).toBeNull()
  })

  it('clears the local session when the server already reports 401', async () => {
    const harness = createHarness({
      logoutError: new AppError('HTTP', 'AUTHENTICATION_REQUIRED', '请重新登录', 'req-1', 401),
    })
    harness.session.saveAuthenticated(authenticated('expired-token'))

    await expect(harness.flow.logout()).resolves.toBe('CLEARED')
    expect(harness.session.getToken()).toBeNull()
  })

  it.each([
    new AppError('NETWORK', 'NETWORK_ERROR', '网络失败'),
    new AppError('HTTP', 'INTERNAL_ERROR', '服务失败', 'req-2', 500),
  ])('preserves the local session when logout cannot be confirmed', async logoutError => {
    const harness = createHarness({ logoutError })
    harness.session.saveAuthenticated(authenticated('active-token'))

    await expect(harness.flow.logout()).rejects.toBe(logoutError)
    expect(harness.session.getToken()).toBe('active-token')
  })
})
