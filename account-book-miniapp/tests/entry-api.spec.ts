import { describe, expect, it, vi } from 'vitest'
import { EntryApi } from '../miniprogram/services/entry'

describe('entry wire contract', () => {
  it('encodes every list filter literally and uses the creators route', async () => {
    const request = vi.fn().mockResolvedValue({ items: [] })
    const api = new EntryApi({ request } as never)

    await api.list({
      dateFrom: '2026-09-01', dateTo: '2026-09-06', entryType: 'EXPENSE',
      categoryId: 7, accountId: 8, createdBy: 2, keyword: '%_\\', page: 3, pageSize: 50,
    })
    await api.creators()

    expect(request.mock.calls).toEqual([
      [{ method: 'GET', path: '/api/v1/entries?dateFrom=2026-09-01&dateTo=2026-09-06&entryType=EXPENSE&categoryId=7&accountId=8&createdBy=2&keyword=%25_%5C&page=3&pageSize=50' }],
      [{ method: 'GET', path: '/api/v1/entries/creators' }],
    ])
  })

  it('uses POST PUT DELETE with exact business bodies and query version', async () => {
    const request = vi.fn().mockResolvedValue({ id: 40 })
    const api = new EntryApi({ request } as never)
    const create = {
      entryType: 'EXPENSE' as const, amount: '3.40', categoryId: 7, accountId: 8,
      entryDate: '2026-09-06', note: '晚餐', clientRequestId: '11111111-2222-4333-8444-555555555555',
    }
    const update = { ...create, version: 2 }
    delete (update as Partial<typeof create>).clientRequestId

    await api.create(create)
    await api.update(40, update)
    await api.remove(40, 2)

    expect(request.mock.calls).toEqual([
      [{ method: 'POST', path: '/api/v1/entries', body: create }],
      [{ method: 'PUT', path: '/api/v1/entries/40', body: update }],
      [{ method: 'DELETE', path: '/api/v1/entries/40?version=2' }],
    ])
  })

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('rejects unsafe entry id %s before HTTP', async id => {
    const request = vi.fn()
    const api = new EntryApi({ request } as never)
    await expect(api.detail(id)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    await expect(api.remove(id, 0)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(request).not.toHaveBeenCalled()
  })
})
