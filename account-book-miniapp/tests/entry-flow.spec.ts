import { describe, expect, it, vi } from 'vitest'
import { EntryCreateIntent, EntryFlow, type EntryGateway } from '../miniprogram/flows/entry-flow'
import type { CreateEntryDraft, Entry } from '../miniprogram/types/entry'
import { AppError } from '../miniprogram/types/error'

const UUID = '11111111-2222-4333-8444-555555555555'
const draft: CreateEntryDraft = {
  entryType: 'EXPENSE', amount: '3.40', categoryId: 7, accountId: 8,
  entryDate: '2026-09-06', note: '晚餐',
}
const entry = { id: 40, amount: '3.40' } as Entry

describe('EntryCreateIntent', () => {
  it('locks one UUID and exact payload after an unknown result until manual retry', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValueOnce(entry)
    const intent = new EntryCreateIntent(create, () => UUID)

    await intent.submit(draft).catch(() => undefined)
    await intent.retry()

    expect(create.mock.calls[1][0]).toEqual(create.mock.calls[0][0])
    expect(create.mock.calls[1][0].clientRequestId).toBe(UUID)
    expect(intent.hasUncertainResult()).toBe(false)
  })

  it('deduplicates a second tap while the first request is pending', async () => {
    let resolve!: (value: Entry) => void
    const pending = new Promise<Entry>(done => { resolve = done })
    const create = vi.fn().mockReturnValue(pending)
    const intent = new EntryCreateIntent(create, () => UUID)

    const first = intent.submit(draft)
    const second = intent.submit({ ...draft, amount: '99.99' })

    expect(create).toHaveBeenCalledTimes(1)
    expect(second).toBe(first)
    resolve(entry)
    await first
  })

  it('keeps no persistent draft and starts a new UUID only after explicit abandon', async () => {
    const create = vi.fn().mockRejectedValue(new Error('offline'))
    const ids = [UUID, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee']
    const intent = new EntryCreateIntent(create, () => ids.shift()!)
    await intent.submit(draft).catch(() => undefined)

    intent.abandon()
    await intent.submit({ ...draft, amount: '4.20' }).catch(() => undefined)

    expect(create.mock.calls[1][0]).toMatchObject({ amount: '4.20', clientRequestId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' })
  })

  it.each([502, 504])('retains the locked UUID for an AppError-shaped ambiguous HTTP %s', async statusCode => {
    const create = vi.fn()
      .mockRejectedValueOnce(new AppError('HTTP', 'HTTP_ERROR', '网关响应异常', 'req-a', statusCode))
      .mockResolvedValueOnce(entry)
    const intent = new EntryCreateIntent(create, () => UUID)

    await intent.submit(draft).catch(() => undefined)
    expect(intent.hasUncertainResult()).toBe(true)
    await intent.retry()

    expect(create.mock.calls[1][0]).toEqual(create.mock.calls[0][0])
  })

  it('blocks ordinary submit after an ambiguous result until retry or abandon is explicit', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new AppError('TIMEOUT', 'REQUEST_TIMEOUT', '请求超时'))
      .mockResolvedValueOnce(entry)
    const intent = new EntryCreateIntent(create, () => UUID)
    await intent.submit(draft).catch(() => undefined)

    await expect(intent.submit({ ...draft, amount: '9.99' }))
      .rejects.toMatchObject({ code: 'ENTRY_INTENT_RETRY_REQUIRED' })
    expect(create).toHaveBeenCalledTimes(1)
    await intent.retry()
    expect(create.mock.calls[1][0]).toMatchObject({ amount: '3.40', clientRequestId: UUID })
  })

  it('releases the payload after a definitive structured 400 rejection', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new AppError('HTTP', 'VALIDATION_FAILED', '请求参数不正确', 'req-a', 400))
      .mockResolvedValueOnce(entry)
    const ids = [UUID, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee']
    const intent = new EntryCreateIntent(create, () => ids.shift()!)

    await intent.submit(draft).catch(() => undefined)
    expect(intent.hasUncertainResult()).toBe(false)
    await intent.submit({ ...draft, amount: '4.20' })

    expect(create.mock.calls[1][0]).toMatchObject({ amount: '4.20', clientRequestId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' })
  })

  it('treats a known unstructured HTTP 400 as a definitive rejection', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new AppError('HTTP', 'HTTP_ERROR', '请求失败', 'req-a', 400))
      .mockResolvedValueOnce(entry)
    const ids = [UUID, 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee']
    const intent = new EntryCreateIntent(create, () => ids.shift()!)

    await intent.submit(draft).catch(() => undefined)
    expect(intent.hasUncertainResult()).toBe(false)
    await intent.submit({ ...draft, amount: '4.20' })

    expect(create.mock.calls[1][0]).toMatchObject({ amount: '4.20', clientRequestId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee' })
  })
})

describe('EntryFlow', () => {
  it('forwards the complete Task8 interface to its gateway', async () => {
    const gateway: EntryGateway = {
      list: vi.fn().mockResolvedValue({ items: [], page: 1, pageSize: 20, total: 0 }),
      detail: vi.fn().mockResolvedValue(entry), creators: vi.fn().mockResolvedValue({ items: [] }),
      create: vi.fn().mockResolvedValue(entry), update: vi.fn().mockResolvedValue(entry),
      remove: vi.fn().mockResolvedValue(undefined),
    }
    const flow = new EntryFlow(gateway, () => UUID)
    await flow.list({ page: 2 })
    await flow.detail(40)
    await flow.creators()
    await flow.create({ ...draft, clientRequestId: UUID })
    await flow.update(40, { ...draft, version: 2 })
    await flow.remove(40, 2)
    expect(gateway.list).toHaveBeenCalledWith({ page: 2 })
    expect(gateway.remove).toHaveBeenCalledWith(40, 2)
  })
})
