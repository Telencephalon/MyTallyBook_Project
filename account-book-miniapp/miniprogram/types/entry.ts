import type { EntryType, ResourceStatus } from './catalog'

export interface Entry {
  id: number
  entryType: EntryType
  amount: string
  categoryId: number
  categoryName: string
  categoryStatus: ResourceStatus
  accountId: number
  accountName: string
  accountStatus: ResourceStatus
  entryDate: string
  personName?: string | null
  note: string | null
  createdBy: number
  creatorName: string
  createdAt: string
  updatedAt: string
  version: number
  canEdit: boolean
  canDelete: boolean
  clientRequestId?: string
}

export interface EntryPage { items: Entry[]; page: number; pageSize: number; total: number }
export interface CreatorOption { userId: number; displayName: string }
export interface CreatorList { items: CreatorOption[] }

export interface EntryFilters {
  dateFrom?: string
  dateTo?: string
  entryType?: EntryType
  categoryId?: number
  accountId?: number
  createdBy?: number
  keyword?: string
  page?: number
  pageSize?: number
}

export interface CreateEntryDraft {
  entryType: EntryType
  amount: string
  categoryId: number
  accountId: number
  entryDate: string
  personName?: string | null
  note: string | null
}
export interface CreateEntry extends CreateEntryDraft { clientRequestId: string }
export interface UpdateEntry extends CreateEntryDraft { version: number }
