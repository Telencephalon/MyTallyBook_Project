import type { MemberRole } from '../types/api'
import { AppError } from '../types/error'

export interface ErrorView {
  message: string
  requestId: string
}

export function toErrorView(error: unknown): ErrorView {
  if (error instanceof AppError) {
    return {
      message: error.message,
      requestId: error.requestId ?? '',
    }
  }

  return {
    message: '操作失败，请稍后重试',
    requestId: '',
  }
}

export function roleLabel(role: MemberRole): string {
  const labels: Record<MemberRole, string> = {
    OWNER: '所有者',
    ADMIN: '管理员',
    MEMBER: '普通成员',
  }
  return labels[role]
}
