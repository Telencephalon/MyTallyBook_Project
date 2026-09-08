import { describe, expect, it } from 'vitest'

import {
  SessionFlow,
  type AuthGateway,
  type LedgerGateway,
  type UserGateway,
} from '../../miniprogram/flows/session-flow'
import { SessionStore, type StorageAdapter } from '../../miniprogram/store/session'
import type { Ledger, UserProfile } from '../../miniprogram/types/api'

class MemoryStorage implements StorageAdapter {
  private readonly values = new Map<string, unknown>()
  get(key: string): unknown { return this.values.get(key) }
  set(key: string, value: unknown): void { this.values.set(key, value) }
  remove(key: string): void { this.values.delete(key) }
}

const NOW = Date.parse('2026-08-31T00:00:00Z')
const user: UserProfile = {
  userId: 1,
  nickname: '原昵称',
  avatarUrl: null,
  ledgerId: 1,
  memberId: 1,
  role: 'OWNER',
  displayName: '原昵称',
}
const ledger: Ledger = {
  id: 1,
  name: '共享账本',
  currency: 'CNY',
  timezone: 'Asia/Shanghai',
  maxMembers: 10,
}

class FakeUsers implements UserGateway {
  updatedNickname: string | null = null
  updateCalls = 0

  async getMe(): Promise<UserProfile> {
    return user
  }

  async updateNickname(nickname: string): Promise<UserProfile> {
    this.updateCalls += 1
    this.updatedNickname = nickname
    return { ...user, nickname, displayName: nickname }
  }
}

function createHarness(withContext = true) {
  const session = new SessionStore(new MemoryStorage(), () => NOW)
  session.saveAuthenticated({
    state: 'AUTHENTICATED',
    token: 'token',
    expiresAt: '2026-09-01T00:00:00Z',
  })
  if (withContext) {
    session.setContext(user, ledger)
  }

  const auth: AuthGateway = {
    async login() { throw new Error('not used') },
    async bootstrap() { throw new Error('not used') },
    async logout() { return { state: 'LOGGED_OUT' } },
  }
  const users = new FakeUsers()
  const ledgers: LedgerGateway = { getFixedLedger: async () => ledger }
  const flow = new SessionFlow(session, auth, users, ledgers, async () => 'not-used')
  return { flow, session, users }
}

describe('SessionFlow.updateNickname', () => {
  it('去除空白后更新昵称并同步会话上下文', async () => {
    const { flow, session, users } = createHarness()

    const updated = await flow.updateNickname('  新昵称  ')

    expect(users.updatedNickname).toBe('新昵称')
    expect(updated.nickname).toBe('新昵称')
    expect(session.getUser()?.nickname).toBe('新昵称')
    expect(session.getLedger()).toEqual(ledger)
  })

  it.each(['   ', '新'.repeat(65)])('非法昵称 %s 不调用接口', async nickname => {
    const { flow, users } = createHarness()

    await expect(flow.updateNickname(nickname)).rejects.toMatchObject({
      code: 'CLIENT_VALIDATION_FAILED',
    })
    expect(users.updateCalls).toBe(0)
  })

  it('标准化后昵称未变化时直接返回当前用户', async () => {
    const { flow, users } = createHarness()

    await expect(flow.updateNickname('  原昵称 ')).resolves.toEqual(user)
    expect(users.updateCalls).toBe(0)
  })

  it('会话上下文缺失时拒绝更新', async () => {
    const { flow, users } = createHarness(false)

    await expect(flow.updateNickname('新昵称')).rejects.toMatchObject({
      code: 'SESSION_CONTEXT_MISSING',
    })
    expect(users.updateCalls).toBe(0)
  })
})
