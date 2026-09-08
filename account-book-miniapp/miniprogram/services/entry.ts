import type { HttpClient } from './http'
import type {
  CreateEntry, CreatorList, Entry, EntryFilters, EntryPage, UpdateEntry,
} from '../types/entry'
import { AppError } from '../types/error'

function requireId(value: number): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效的账单')
  }
}

function requireVersion(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > 4294967295) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '账单版本无效，请刷新')
  }
}

function query(filters: EntryFilters): string {
  const values: Array<[string, string | number | undefined]> = [
    ['dateFrom', filters.dateFrom], ['dateTo', filters.dateTo], ['entryType', filters.entryType],
    ['categoryId', filters.categoryId], ['accountId', filters.accountId], ['createdBy', filters.createdBy],
    ['keyword', filters.keyword], ['page', filters.page], ['pageSize', filters.pageSize],
  ]
  const parts = values.filter(([, value]) => value !== undefined && value !== '')
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export class EntryApi {
  constructor(private readonly http: HttpClient) {}

  list(filters: EntryFilters = {}): Promise<EntryPage> {
    for (const value of [filters.categoryId, filters.accountId, filters.createdBy]) {
      if (value !== undefined) requireId(value)
    }
    if (filters.page !== undefined) requireId(filters.page)
    if (filters.pageSize !== undefined && (!Number.isSafeInteger(filters.pageSize) || filters.pageSize < 1 || filters.pageSize > 50)) {
      throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '分页参数无效')
    }
    return this.http.request({ method: 'GET', path: `/api/v1/entries${query(filters)}` })
  }

  async detail(id: number): Promise<Entry> {
    requireId(id)
    return this.http.request({ method: 'GET', path: `/api/v1/entries/${id}` })
  }

  creators(): Promise<CreatorList> {
    return this.http.request({ method: 'GET', path: '/api/v1/entries/creators' })
  }

  create(body: CreateEntry): Promise<Entry> {
    return this.http.request({ method: 'POST', path: '/api/v1/entries', body })
  }

  async update(id: number, body: UpdateEntry): Promise<Entry> {
    requireId(id)
    requireVersion(body.version)
    return this.http.request({ method: 'PUT', path: `/api/v1/entries/${id}`, body })
  }

  async remove(id: number, version: number): Promise<void> {
    requireId(id)
    requireVersion(version)
    return this.http.request({ method: 'DELETE', path: `/api/v1/entries/${id}?version=${version}` })
  }
}
