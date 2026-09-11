import type { AuthStateData, Ledger, UserProfile } from '../types/api'
import { AppError } from '../types/error'

export const SESSION_STORAGE_KEY = 'mytallybook.session.v1'

export interface StorageAdapter {
  get(key: string): unknown
  set(key: string, value: unknown): void
  remove(key: string): void
}

interface PersistedSession {
  version: 1
  token: string
  expiresAt: string
  environmentId: string
}

export class SessionStore {
  private token: string | null = null
  private user: UserProfile | null = null
  private ledger: Ledger | null = null
  // In-memory identity epoch; never persisted. A clear while anonymous still cancels old work.
  private revision = 0

  constructor(
    private readonly storage: StorageAdapter,
    private readonly now: () => number = Date.now,
    private readonly environmentId = 'develop|http://127.0.0.1:7631',
  ) {}

  hydrate(): boolean {
    const persisted = this.storage.get(SESSION_STORAGE_KEY)
    if (!this.isValidPersistedSession(persisted) || persisted.environmentId !== this.environmentId) {
      this.clear()
      return false
    }

    ++this.revision
    this.token = persisted.token
    this.user = null
    this.ledger = null
    return true
  }

  saveAuthenticated(auth: AuthStateData): void {
    const expiresAtMs = auth.expiresAt === null ? Number.NaN : Date.parse(auth.expiresAt)
    if (
      auth.state !== 'AUTHENTICATED'
      || typeof auth.token !== 'string'
      || auth.token.length === 0
      || auth.expiresAt === null
      || !Number.isFinite(expiresAtMs)
      || expiresAtMs <= this.now()
    ) {
      throw new AppError(
        'INVALID_RESPONSE',
        'INVALID_AUTH_RESPONSE',
        '服务器返回的登录状态不完整，请重试',
      )
    }

    const persisted: PersistedSession = {
      version: 1,
      token: auth.token,
      expiresAt: auth.expiresAt,
      environmentId: this.environmentId,
    }
    this.storage.set(SESSION_STORAGE_KEY, persisted)
    ++this.revision
    this.token = auth.token
    this.user = null
    this.ledger = null
  }

  setContext(user: UserProfile, ledger: Ledger): void {
    this.user = user
    this.ledger = ledger
  }

  getToken(): string | null {
    return this.token
  }

  getEnvironmentId(): string {
    return this.environmentId
  }

  getRevision(): number {
    return this.revision
  }

  getUser(): UserProfile | null {
    return this.user
  }

  getLedger(): Ledger | null {
    return this.ledger
  }

  clear(): void {
    this.storage.remove(SESSION_STORAGE_KEY)
    ++this.revision
    this.token = null
    this.user = null
    this.ledger = null
  }

  private isValidPersistedSession(value: unknown): value is PersistedSession {
    if (typeof value !== 'object' || value === null) {
      return false
    }

    const candidate = value as Partial<PersistedSession>
    if (
      candidate.version !== 1
      || typeof candidate.token !== 'string'
      || candidate.token.length === 0
      || typeof candidate.expiresAt !== 'string'
    ) {
      return false
    }

    const expiresAtMs = Date.parse(candidate.expiresAt)
    return Number.isFinite(expiresAtMs) && expiresAtMs > this.now()
  }
}

const wechatStorage: StorageAdapter = {
  get: key => wx.getStorageSync(key) as unknown,
  set: (key, value) => wx.setStorageSync(key, value),
  remove: key => wx.removeStorageSync(key),
}

export const sessionStore = new SessionStore(wechatStorage)

export function createSessionStore(environmentId: string): SessionStore {
  return new SessionStore(wechatStorage, Date.now, environmentId)
}
