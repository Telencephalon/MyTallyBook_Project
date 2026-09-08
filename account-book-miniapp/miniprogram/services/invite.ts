import type { AuthStateData, CreatedInvite, InvitePage, InviteQuery, RevokedInvite } from '../types/api'
import { AppError } from '../types/error'
import type { HttpClient } from './http'
import { requireSafeId } from './member'

export class InviteApi {
  constructor(private readonly http: HttpClient) {}
  async create(expiresInHours = 24): Promise<CreatedInvite> {
    if (!Number.isInteger(expiresInHours) || expiresInHours < 1 || expiresInHours > 168) {
      throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '有效期须为 1～168 小时的整数')
    }
    return this.http.request({ method: 'POST', path: '/api/v1/invites', body: { expiresInHours } })
  }
  async list(query: InviteQuery = {}): Promise<InvitePage> {
    const page = query.page ?? 1
    const pageSize = query.pageSize ?? 20
    if (!Number.isSafeInteger(page) || page < 1 || page > 2147483647 || !Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50
      || (page - 1) * pageSize > 2147483647
      || (query.status !== undefined && !['ACTIVE', 'USED', 'REVOKED', 'EXPIRED'].includes(query.status))) {
      throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '邀请列表筛选或分页参数无效')
    }
    const status = query.status ? `&status=${query.status}` : ''
    return this.http.request({ method: 'GET', path: `/api/v1/invites?page=${page}&pageSize=${pageSize}${status}` })
  }
  async revoke(inviteId: number): Promise<RevokedInvite> {
    requireSafeId(inviteId)
    return this.http.request({ method: 'DELETE', path: `/api/v1/invites/${inviteId}` })
  }
  accept(code: string, inviteToken: string): Promise<AuthStateData> {
    return this.http.request({ method: 'POST', path: '/api/v1/auth/invites/accept', body: { code, inviteToken }, authenticated: false })
  }
}
