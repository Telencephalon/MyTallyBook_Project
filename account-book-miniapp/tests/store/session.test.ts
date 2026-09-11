import { describe, expect, it } from 'vitest'
import {
  SESSION_STORAGE_KEY,
  SessionStore,
  type StorageAdapter,
} from '../../miniprogram/store/session'
import type { Ledger, UserProfile } from '../../miniprogram/types/api'

class MemoryStorage implements StorageAdapter {
  readonly values = new Map<string, unknown>()

  get(key: string): unknown {
    return this.values.get(key)
  }

  set(key: string, value: unknown): void {
    this.values.set(key, value)
  }

  remove(key: string): void {
    this.values.delete(key)
  }
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

function createStore(): { storage: MemoryStorage; store: SessionStore } {
  const storage = new MemoryStorage()
    return {
      storage,
    store: new SessionStore(storage, () => NOW, 'develop|http://127.0.0.1:7631'),
  }
}

describe('SessionStore', () => {
  it('persists only the versioned token and expiry for an authenticated response', () => {
    const { storage, store } = createStore()

    store.saveAuthenticated({
      state: 'AUTHENTICATED',
      token: 'raw-session-token',
      expiresAt: FUTURE,
    })

    expect(storage.values.get(SESSION_STORAGE_KEY)).toEqual({
      version: 1,
      token: 'raw-session-token',
      expiresAt: FUTURE,
      environmentId: 'develop|http://127.0.0.1:7631',
    })
    expect(store.getToken()).toBe('raw-session-token')
  })

  it('hydrates a valid persisted session', () => {
    const { storage, store } = createStore()
    storage.values.set(SESSION_STORAGE_KEY, {
      version: 1,
      token: 'persisted-token',
      expiresAt: FUTURE,
      environmentId: 'develop|http://127.0.0.1:7631',
    })

    expect(store.hydrate()).toBe(true)
    expect(store.getToken()).toBe('persisted-token')
  })

  it('does not restore a token from another environment or a legacy record', () => {
    const { storage, store } = createStore()
    storage.values.set(SESSION_STORAGE_KEY, {
      version: 1,
      token: 'wrong-environment',
      expiresAt: FUTURE,
      environmentId: 'release|https://api.example.com',
    })
    expect(store.hydrate()).toBe(false)
    expect(storage.values.has(SESSION_STORAGE_KEY)).toBe(false)

    storage.values.set(SESSION_STORAGE_KEY, { version: 1, token: 'legacy', expiresAt: FUTURE })
    expect(store.hydrate()).toBe(false)
    expect(storage.values.has(SESSION_STORAGE_KEY)).toBe(false)
  })

  it.each([
    { version: 1, token: 'expired', expiresAt: '2026-08-30T00:00:00Z' },
    { version: 1, token: '', expiresAt: FUTURE },
    { version: 1, token: 'token', expiresAt: 'not-a-date' },
    { version: 2, token: 'token', expiresAt: FUTURE },
    'malformed',
  ])('removes an expired, malformed, or unsupported persisted value: %j', persisted => {
    const { storage, store } = createStore()
    storage.values.set(SESSION_STORAGE_KEY, persisted)

    expect(store.hydrate()).toBe(false)
    expect(store.getToken()).toBeNull()
    expect(storage.values.has(SESSION_STORAGE_KEY)).toBe(false)
  })

  it.each([
    { state: 'NEED_BOOTSTRAP' as const, token: null, expiresAt: null },
    { state: 'AUTHENTICATED' as const, token: null, expiresAt: FUTURE },
    { state: 'AUTHENTICATED' as const, token: 'token', expiresAt: 'invalid' },
    { state: 'AUTHENTICATED' as const, token: 'token', expiresAt: '2026-08-30T00:00:00Z' },
  ])('rejects incomplete or expired authentication without writing Storage: %j', auth => {
    const { storage, store } = createStore()

    expect(() => store.saveAuthenticated(auth)).toThrowError(
      expect.objectContaining({ code: 'INVALID_AUTH_RESPONSE' }),
    )
    expect(storage.values.has(SESSION_STORAGE_KEY)).toBe(false)
  })

  it('keeps user and ledger only in memory and clears all state', () => {
    const { storage, store } = createStore()
    store.saveAuthenticated({
      state: 'AUTHENTICATED',
      token: 'token',
      expiresAt: FUTURE,
    })

    store.setContext(user, ledger)

    expect(store.getUser()).toEqual(user)
    expect(store.getLedger()).toEqual(ledger)
    expect(storage.values.get(SESSION_STORAGE_KEY)).not.toHaveProperty('user')
    expect(storage.values.get(SESSION_STORAGE_KEY)).not.toHaveProperty('ledger')

    store.clear()

    expect(store.getToken()).toBeNull()
    expect(store.getUser()).toBeNull()
    expect(store.getLedger()).toBeNull()
    expect(storage.values.has(SESSION_STORAGE_KEY)).toBe(false)
  })
})
