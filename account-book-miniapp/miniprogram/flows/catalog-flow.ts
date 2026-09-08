import type { CatalogApi } from '../services/catalog'
import type {
  CreateAccount, CreateCategory, EntryType, ResourceStatus, UpdateAccount, UpdateCategory,
} from '../types/catalog'

export class CatalogFlow {
  constructor(private readonly api: CatalogApi) {}

  categories(entryType?: EntryType, status?: ResourceStatus) { return this.api.categories(entryType, status) }
  category(id: number) { return this.api.category(id) }
  createCategory(body: CreateCategory) { return this.api.createCategory(body) }
  updateCategory(id: number, body: UpdateCategory) { return this.api.updateCategory(id, body) }
  deleteCategory(id: number) { return this.api.deleteCategory(id) }
  accounts(status?: ResourceStatus) { return this.api.accounts(status) }
  account(id: number) { return this.api.account(id) }
  createAccount(body: CreateAccount) { return this.api.createAccount(body) }
  updateAccount(id: number, body: UpdateAccount) { return this.api.updateAccount(id, body) }
  deleteAccount(id: number, version: number) { return this.api.deleteAccount(id, version) }
}
