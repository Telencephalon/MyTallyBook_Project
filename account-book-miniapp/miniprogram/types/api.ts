export type AuthState = 'AUTHENTICATED' | 'NEED_BOOTSTRAP' | 'INVITE_REQUIRED'

export type MemberRole = 'OWNER' | 'ADMIN' | 'MEMBER'

export interface ApiResponse<T> {
  code: 'OK'
  message: string
  data: T
  requestId: string
  timestamp: string
}

export interface ApiErrorResponse {
  code: string
  message: string
  details?: unknown
  requestId: string
  timestamp: string
}

export interface AuthStateData {
  state: AuthState
  token: string | null
  expiresAt: string | null
}

export interface UserProfile {
  userId: number
  nickname: string
  avatarUrl: string | null
  ledgerId: number
  memberId: number
  role: MemberRole
  displayName: string | null
}

export interface Ledger {
  id: number
  name: string
  currency: string
  timezone: string
  maxMembers: number
}

export interface UpdateProfileInput {
  nickname: string
}

export type InviteStatus = 'ACTIVE' | 'USED' | 'REVOKED' | 'EXPIRED'
export interface CreatedInvite {
  id: number
  token: string
  expiresAt: string
  status: 'ACTIVE'
}
export interface InviteView {
  id: number
  createdBy: number
  createdByName: string
  createdAt: string
  expiresAt: string
  status: InviteStatus
  usedBy: number | null
  usedAt: string | null
}
export interface InviteQuery { page?: number; pageSize?: number; status?: InviteStatus }
export interface InvitePage { items: InviteView[]; page: number; pageSize: number; total: number }
export interface RevokedInvite { id: number; status: InviteStatus }
export interface MemberView {
  memberId: number
  userId: number
  nickname: string
  displayName: string | null
  role: MemberRole
  joinedAt: string
}
export interface MemberList { items: MemberView[]; activeCount: number; maxMembers: number; ownerUserId: number }
export interface RemovedMember { memberId: number; status: 'REMOVED' | 'LEFT' }
export interface OwnershipTransfer { ownerUserId: number; previousOwnerUserId: number }
