import type { CreateEntryDraft } from '../types/entry'
import type { EntryType } from '../types/catalog'
import { AppError } from '../types/error'

const MONEY = /^(?:0|[1-9]\d{0,12})(?:\.\d{1,2})?$/
const DATE = /^\d{4}-\d{2}-\d{2}$/

export function shanghaiToday(now = Date.now()): string {
  return new Date(now + 8 * 60 * 60 * 1000).toISOString().slice(0, 10)
}

export function entryDraft(values: {
  entryType: EntryType; amount: string; categoryId: number; accountId: number;
  entryDate: string; note: string; personName?: string,
}): CreateEntryDraft {
  const amount = values.amount.trim()
  if (!MONEY.test(amount) || Number(amount) <= 0 || amount.length > 16) invalid('金额格式不正确')
  if (!Number.isSafeInteger(values.categoryId) || values.categoryId <= 0
      || !Number.isSafeInteger(values.accountId) || values.accountId <= 0) invalid('请选择分类和账户')
  const parsedDate = new Date(`${values.entryDate}T00:00:00Z`)
  if (!DATE.test(values.entryDate) || Number.isNaN(parsedDate.getTime())
      || parsedDate.toISOString().slice(0, 10) !== values.entryDate
      || values.entryDate < '1000-01-01' || values.entryDate > '9999-12-31') invalid('日期格式不正确')
  if (Array.from(values.note).length > 500) invalid('备注不能超过500个字符')
  const personName = values.personName?.trim() || null
  if (personName && Array.from(personName).length > 64) invalid('人名不能超过64个字符')
  return { ...(values.personName !== undefined ? { personName } : {}), entryType: values.entryType, amount, categoryId: values.categoryId,
    accountId: values.accountId, entryDate: values.entryDate, note: values.note || null }
}

export function validEntryId(value: string | number | undefined): number {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) invalid('账单编号无效')
  return id
}

function invalid(message: string): never {
  throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', message)
}
