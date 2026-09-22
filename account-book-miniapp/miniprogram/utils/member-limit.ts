import { AppError } from '../types/error'

export function requireMemberLimit(value: unknown): asserts value is number | null {
  if (value !== null && (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0)) {
    throw new AppError('INVALID_RESPONSE', 'INVALID_RESPONSE', '服务器返回的成员信息不完整，请重试')
  }
}
