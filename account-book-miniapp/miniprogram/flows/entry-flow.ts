import type {
  CreateEntry, CreateEntryDraft, CreatorList, Entry, EntryFilters, EntryPage, UpdateEntry,
} from '../types/entry'
import { AppError } from '../types/error'

export interface EntryGateway {
  list(filters?: EntryFilters): Promise<EntryPage>
  detail(id: number): Promise<Entry>
  creators(): Promise<CreatorList>
  create(body: CreateEntry): Promise<Entry>
  update(id: number, body: UpdateEntry): Promise<Entry>
  remove(id: number, version: number): Promise<void>
}

export function newClientRequestId(random = Math.random): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, token => {
    const value = Math.floor(random() * 16)
    return (token === 'x' ? value : (value & 0x3) | 0x8).toString(16)
  })
}

export class EntryCreateIntent {
  private payload: CreateEntry | null = null
  private pending: Promise<Entry> | null = null
  private completed: Entry | null = null
  private uncertain = false

  constructor(
    private readonly createEntry: (body: CreateEntry) => Promise<Entry>,
    private readonly uuid: () => string = newClientRequestId,
  ) {}

  submit(draft: CreateEntryDraft): Promise<Entry> {
    if (this.pending) return this.pending
    if (this.completed) return Promise.resolve(this.completed)
    if (this.uncertain) {
      return Promise.reject(new AppError(
        'INVALID_RESPONSE',
        'ENTRY_INTENT_RETRY_REQUIRED',
        '上次提交结果未知，请使用原提交重试或确认放弃',
      ))
    }
    if (!this.payload) this.payload = { ...draft, clientRequestId: this.uuid().toLowerCase() }
    return this.run()
  }

  retry(): Promise<Entry> {
    if (this.pending) return this.pending
    if (!this.payload || !this.uncertain) return Promise.reject(new Error('没有可重试的新增账单'))
    return this.run()
  }

  hasUncertainResult(): boolean { return this.uncertain }
  isSubmitting(): boolean { return this.pending !== null }
  pendingResult(): Promise<Entry> | null { return this.pending }
  completedResult(): Entry | null { return this.completed }

  consumeCompletedResult(): Entry | null {
    const result = this.completed
    if (result) {
      this.completed = null
      this.payload = null
    }
    return result
  }

  abandon(): void {
    if (this.pending) return
    this.payload = null
    this.completed = null
    this.uncertain = false
  }

  private run(): Promise<Entry> {
    const payload = { ...this.payload! }
    let tracked!: Promise<Entry>
    tracked = this.createEntry(payload).then(result => {
      this.uncertain = false
      this.completed = result
      return result
    }, error => {
      this.uncertain = isAmbiguousOutcome(error)
      if (!this.uncertain) this.payload = null
      throw error
    }).finally(() => {
      if (this.pending === tracked) this.pending = null
    })
    this.pending = tracked
    return tracked
  }
}

function isAmbiguousOutcome(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return true
  const candidate = error as { kind?: unknown; statusCode?: unknown }
  if (candidate.kind === 'NETWORK' || candidate.kind === 'TIMEOUT' || candidate.kind === 'INVALID_RESPONSE') return true
  if (candidate.kind !== 'HTTP') return candidate.kind === undefined
  const status = typeof candidate.statusCode === 'number' ? candidate.statusCode : 0
  return status === 0 || status >= 500 || status === 408 || status === 425 || status === 429
}

export class EntryFlow {
  constructor(private readonly api: EntryGateway, private readonly uuid: () => string = newClientRequestId) {}
  list(filters: EntryFilters = {}) { return this.api.list(filters) }
  detail(id: number) { return this.api.detail(id) }
  creators() { return this.api.creators() }
  create(body: CreateEntry) { return this.api.create(body) }
  update(id: number, body: UpdateEntry) { return this.api.update(id, body) }
  remove(id: number, version: number) { return this.api.remove(id, version) }
  newCreateIntent() { return new EntryCreateIntent(body => this.create(body), this.uuid) }
}
