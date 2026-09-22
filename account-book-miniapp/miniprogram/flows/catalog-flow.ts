import type { CatalogApi } from '../services/catalog'
import type { MemberRole } from '../types/api'
import { AppError } from '../types/error'
import type {
  CreateAccount, CreateCategory, EntryType, ResourceStatus, UpdateAccount, UpdateCategory,
} from '../types/catalog'

export class CatalogFlow {
  constructor(private readonly api: CatalogApi, private readonly currentRole: () => MemberRole | undefined) {}

  private requireOwner() {
    if (this.currentRole() !== 'OWNER') throw new AppError('HTTP', 'ACCESS_DENIED', '共享分类和资金账户由所有者维护')
  }

  categories(entryType?: EntryType, status?: ResourceStatus) { return this.api.categories(entryType, status) }
  category(id: number) { return this.api.category(id) }
  createCategory(body: CreateCategory) { this.requireOwner(); return this.api.createCategory(body) }
  updateCategory(id: number, body: UpdateCategory) { this.requireOwner(); return this.api.updateCategory(id, body) }
  deleteCategory(id: number) { this.requireOwner(); return this.api.deleteCategory(id) }
  accounts(status?: ResourceStatus) { return this.api.accounts(status) }
  account(id: number) { return this.api.account(id) }
  createAccount(body: CreateAccount) { this.requireOwner(); return this.api.createAccount(body) }
  updateAccount(id: number, body: UpdateAccount) { this.requireOwner(); return this.api.updateAccount(id, body) }
  deleteAccount(id: number, version: number) { this.requireOwner(); return this.api.deleteAccount(id, version) }
}
