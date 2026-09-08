import type {
  Account, Category, CreateAccount, CreateCategory, EntryType,
  ItemList, ResourceStatus, UpdateAccount, UpdateCategory,
} from '../types/catalog'
import { AppError } from '../types/error'
import type { HttpClient } from './http'

function requireId(value: number): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效的记录')
  }
}

function requireVersion(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > 4294967295) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '记录版本无效，请刷新')
  }
}

function query(values: Record<string, string | undefined>): string {
  const parts = Object.entries(values)
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${key}=${encodeURIComponent(value!)}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export class CatalogApi {
  constructor(private readonly http: HttpClient) {}

  categories(entryType?: EntryType, status?: ResourceStatus): Promise<ItemList<Category>> {
    return this.http.request({ method: 'GET', path: `/api/v1/categories${query({ entryType, status })}` })
  }

  async category(categoryId: number): Promise<Category> {
    requireId(categoryId)
    return this.http.request({ method: 'GET', path: `/api/v1/categories/${categoryId}` })
  }

  createCategory(body: CreateCategory): Promise<Category> {
    return this.http.request({ method: 'POST', path: '/api/v1/categories', body })
  }

  async updateCategory(categoryId: number, body: UpdateCategory): Promise<Category> {
    requireId(categoryId)
    return this.http.request({ method: 'PUT', path: `/api/v1/categories/${categoryId}`, body })
  }

  async deleteCategory(categoryId: number): Promise<void> {
    requireId(categoryId)
    return this.http.request({ method: 'DELETE', path: `/api/v1/categories/${categoryId}` })
  }

  accounts(status?: ResourceStatus): Promise<ItemList<Account>> {
    return this.http.request({ method: 'GET', path: `/api/v1/accounts${query({ status })}` })
  }

  async account(accountId: number): Promise<Account> {
    requireId(accountId)
    return this.http.request({ method: 'GET', path: `/api/v1/accounts/${accountId}` })
  }

  createAccount(body: CreateAccount): Promise<Account> {
    return this.http.request({ method: 'POST', path: '/api/v1/accounts', body })
  }

  async updateAccount(accountId: number, body: UpdateAccount): Promise<Account> {
    requireId(accountId)
    requireVersion(body.version)
    return this.http.request({ method: 'PUT', path: `/api/v1/accounts/${accountId}`, body })
  }

  async deleteAccount(accountId: number, version: number): Promise<void> {
    requireId(accountId)
    requireVersion(version)
    return this.http.request({ method: 'DELETE', path: `/api/v1/accounts/${accountId}?version=${version}` })
  }
}
