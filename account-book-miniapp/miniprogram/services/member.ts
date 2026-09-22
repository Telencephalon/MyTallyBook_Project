import type { MemberList, MemberView, OwnershipTransfer, RemovedMember } from '../types/api'
import { AppError } from '../types/error'
import type { HttpClient } from './http'
import { requireMemberLimit } from '../utils/member-limit'

export function requireSafeId(id: number): void {
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效的记录')
  }
}

export class MemberApi {
  constructor(private readonly http: HttpClient) {}
  async list(): Promise<MemberList> {
    const members = await this.http.request<MemberList>({ method: 'GET', path: '/api/v1/members' })
    requireMemberLimit(members?.maxMembers)
    return members
  }
  async changeRole(memberId: number, role: 'ADMIN' | 'MEMBER'): Promise<MemberView> {
    requireSafeId(memberId)
    if (role !== 'ADMIN' && role !== 'MEMBER') {
      throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '只能设置管理员或普通成员')
    }
    return this.http.request({ method: 'PUT', path: `/api/v1/members/${memberId}/role`, body: { role } })
  }
  async remove(memberId: number): Promise<RemovedMember> {
    requireSafeId(memberId)
    return this.http.request({ method: 'DELETE', path: `/api/v1/members/${memberId}` })
  }
  async transfer(memberId: number): Promise<OwnershipTransfer> {
    requireSafeId(memberId)
    return this.http.request({ method: 'POST', path: `/api/v1/members/${memberId}/transfer-ownership` })
  }
}
