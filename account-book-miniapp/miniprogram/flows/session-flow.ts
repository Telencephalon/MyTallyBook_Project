import type { SessionStore } from '../store/session'
import type { AuthStateData, Ledger, UserProfile } from '../types/api'
import { AppError } from '../types/error'
import { validateBootstrapKey, validateNickname } from '../utils/validation'

export interface AuthGateway {
  login(code: string): Promise<AuthStateData>
  bootstrap(code: string, bootstrapKey: string): Promise<AuthStateData>
  logout(): Promise<{ state: string }>
}

export interface UserGateway {
  getMe(): Promise<UserProfile>
  updateNickname(nickname: string): Promise<UserProfile>
}

export interface LedgerGateway {
  getFixedLedger(): Promise<Ledger>
}

export type EntryDestination = 'HOME' | 'BOOTSTRAP' | 'INVITE_REQUIRED'

export interface EntryResult {
  destination: EntryDestination
}

export type LogoutResult = 'CLEARED' | 'ALREADY_HANDLED' | 'SUPERSEDED'

export class SessionFlow {
  private contextGeneration = 0
  private authenticationGeneration = 0
  constructor(
    private readonly session: SessionStore,
    private readonly auth: AuthGateway,
    private readonly users: UserGateway,
    private readonly ledgers: LedgerGateway,
    private readonly obtainWechatCode: () => Promise<string>,
  ) {}

  async start(): Promise<EntryResult> {
    if (this.session.hydrate()) {
      await this.refreshContext()
      return { destination: 'HOME' }
    }

    const assertCurrent = this.beginAuthenticationAttempt()
    const code = await this.obtainWechatCode()
    assertCurrent()
    const auth = await this.auth.login(code)
    assertCurrent()
    return this.applyLoginResult(auth, assertCurrent)
  }

  async bootstrap(bootstrapKey: string): Promise<EntryResult> {
    const validation = validateBootstrapKey(bootstrapKey)
    if (!validation.valid) {
      throw new AppError(
        'INVALID_RESPONSE',
        'CLIENT_VALIDATION_FAILED',
        validation.message,
      )
    }

    const assertCurrent = this.beginAuthenticationAttempt()
    const code = await this.obtainWechatCode()
    assertCurrent()
    const auth = await this.auth.bootstrap(code, validation.value)
    if (auth.state !== 'AUTHENTICATED') {
      throw new AppError(
        'INVALID_RESPONSE',
        'INVALID_AUTH_RESPONSE',
        '服务器返回的初始化状态不完整，请重试',
      )
    }
    await this.establishAuthenticatedSession(auth, assertCurrent)
    return { destination: 'HOME' }
  }

  async refreshContext(): Promise<void> {
    const token = this.session.getToken()
    const revision = this.session.getRevision()
    const generation = ++this.contextGeneration
    const [user, ledger] = await Promise.all([
      this.users.getMe(),
      this.ledgers.getFixedLedger(),
    ])
    if (token !== this.session.getToken() || revision !== this.session.getRevision() || generation !== this.contextGeneration) {
      throw new AppError('INVALID_RESPONSE', 'SESSION_CONTEXT_CHANGED', '登录身份或权限已变化，请刷新页面')
    }
    this.session.setContext(user, ledger)
  }

  async updateNickname(nickname: string, isActive: () => boolean = () => true): Promise<UserProfile> {
    const validation = validateNickname(nickname)
    if (!validation.valid) {
      throw new AppError(
        'INVALID_RESPONSE',
        'CLIENT_VALIDATION_FAILED',
        validation.message,
      )
    }

    const currentUser = this.session.getUser()
    const currentLedger = this.session.getLedger()
    if (!currentUser || !currentLedger) {
      throw new AppError(
        'INVALID_RESPONSE',
        'SESSION_CONTEXT_MISSING',
        '登录状态不完整，请重新登录',
      )
    }

    if (validation.value === currentUser.nickname) {
      return currentUser
    }

    const isCurrent = this.captureContextGuard()
    const updatedUser = await this.users.updateNickname(validation.value)
    if (!isActive() || !isCurrent()) {
      throw new AppError('INVALID_RESPONSE', 'SESSION_CONTEXT_CHANGED', '登录身份或权限已变化；昵称修改可能已生效，请刷新资料确认')
    }
    this.session.setContext(updatedUser, currentLedger)
    return updatedUser
  }

  // Shared by a context mutation and its Page continuation. A refresh invalidates
  // the snapshot as soon as it starts, even if the token did not change.
  captureContextGuard(): () => boolean {
    const token = this.session.getToken()
    const revision = this.session.getRevision()
    const generation = this.contextGeneration
    return () => token === this.session.getToken()
      && revision === this.session.getRevision()
      && generation === this.contextGeneration
  }

  async logout(): Promise<LogoutResult> {
    const token = this.session.getToken()
    const revision = this.session.getRevision()
    const isCurrent = () => token === this.session.getToken() && revision === this.session.getRevision()
    try {
      await this.auth.logout()
      if (!isCurrent()) return 'SUPERSEDED'
      this.session.clear()
      return 'CLEARED'
    } catch (error) {
      if (error instanceof AppError && error.statusCode === 401) {
        if (isCurrent()) {
          this.session.clear()
          return 'CLEARED'
        }
        // The runtime's current-token 401 handler already clears and relaunches.
        // Do not clear twice, and never reinterpret a newer identity as logout.
        return this.session.getToken() === null && this.session.getRevision() === revision + 1
          ? 'ALREADY_HANDLED' : 'SUPERSEDED'
      }
      throw error
    }
  }

  private async applyLoginResult(auth: AuthStateData, assertCurrent: () => void): Promise<EntryResult> {
    if (auth.state === 'NEED_BOOTSTRAP') {
      return { destination: 'BOOTSTRAP' }
    }
    if (auth.state === 'INVITE_REQUIRED') {
      return { destination: 'INVITE_REQUIRED' }
    }

    await this.establishAuthenticatedSession(auth, assertCurrent)
    return { destination: 'HOME' }
  }

  beginAuthenticationAttempt(isActive: () => boolean = () => true): () => void {
    const revision = this.session.getRevision()
    const generation = ++this.authenticationGeneration
    return () => {
      if (!isActive() || revision !== this.session.getRevision() || generation !== this.authenticationGeneration) {
        throw new AppError('INVALID_RESPONSE', 'AUTHENTICATION_SUPERSEDED', '操作已取消或登录状态已更新；如已加入，请通过普通登录恢复')
      }
    }
  }

  async establishAuthenticatedSession(auth: AuthStateData, assertCurrent: () => void = () => {}): Promise<void> {
    assertCurrent()
    this.session.saveAuthenticated(auth)
    await this.refreshContext()
  }
}
