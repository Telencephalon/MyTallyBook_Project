import type { InviteApi } from '../services/invite'
import type { InviteQuery } from '../types/api'
import { AppError } from '../types/error'
import type { SessionFlow } from './session-flow'

export class InviteFlow {
  constructor(
    private readonly api: InviteApi,
    private readonly sessionFlow: SessionFlow,
    private readonly obtainWechatCode: () => Promise<string>,
  ) {}
  async create(hours = 24) {
    const invite = await this.api.create(hours)
    await this.sessionFlow.refreshContext()
    return invite
  }
  list(query: InviteQuery = {}) { return this.api.list(query) }
  async revoke(id: number) {
    const result = await this.api.revoke(id)
    await this.sessionFlow.refreshContext()
    return result
  }
  async accept(rawToken: string, isActive: () => boolean = () => true): Promise<void> {
    const inviteToken = rawToken.trim()
    if (!/^[A-Za-z0-9_-]{43}$/.test(inviteToken)) {
      throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请输入完整的 43 位邀请码，注意大小写')
    }
    const assertCurrent = this.sessionFlow.beginAuthenticationAttempt(isActive)
    const code = await this.obtainWechatCode()
    assertCurrent()
    const auth = await this.api.accept(code, inviteToken)
    await this.sessionFlow.establishAuthenticatedSession(auth, assertCurrent)
  }
}
