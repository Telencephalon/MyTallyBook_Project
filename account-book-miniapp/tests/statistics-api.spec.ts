import { describe, expect, it, vi } from 'vitest'
import { StatisticsApi } from '../miniprogram/services/statistics'

describe('statistics wire contract', () => {
  it('uses the five exact GET paths and only ranking requests carry direction', async () => {
    const request = vi.fn().mockResolvedValue({ items: [] })
    const api = new StatisticsApi({ request } as never)

    await api.summary('2024-09')
    await api.daily('2024-09')
    await api.categories('2024-09', 'INCOME')
    await api.accounts('2024-09')
    await api.members('2024-09', 'EXPENSE')

    expect(request.mock.calls).toEqual([
      [{ method: 'GET', path: '/api/v1/statistics/monthly-summary?month=2024-09' }],
      [{ method: 'GET', path: '/api/v1/statistics/daily-trend?month=2024-09' }],
      [{ method: 'GET', path: '/api/v1/statistics/categories?month=2024-09&entryType=INCOME' }],
      [{ method: 'GET', path: '/api/v1/statistics/accounts?month=2024-09' }],
      [{ method: 'GET', path: '/api/v1/statistics/members?month=2024-09&entryType=EXPENSE' }],
    ])
  })

  it('omits optional query values and lets the server apply defaults', async () => {
    const request = vi.fn().mockResolvedValue({})
    const api = new StatisticsApi({ request } as never)

    await api.summary()
    await api.categories()

    expect(request.mock.calls).toEqual([
      [{ method: 'GET', path: '/api/v1/statistics/monthly-summary' }],
      [{ method: 'GET', path: '/api/v1/statistics/categories' }],
    ])
  })

  it('emits all-time custom range and daily page query parameters', async () => {
    const request = vi.fn().mockResolvedValue({ items: [] })
    const api = new StatisticsApi({ request } as never)

    await api.summary({ rangeType: 'ALL' })
    await api.accounts({ startDate: '2024-02-29', endDate: '2024-03-01' })
    await api.daily({ startDate: '2024-01-15', endDate: '2024-03-05', page: 2 })
    await api.categories({ rangeType: 'ALL' }, 'INCOME')

    expect(request.mock.calls).toEqual([
      [{ method: 'GET', path: '/api/v1/statistics/monthly-summary?range=all' }],
      [{ method: 'GET', path: '/api/v1/statistics/accounts?startDate=2024-02-29&endDate=2024-03-01' }],
      [{ method: 'GET', path: '/api/v1/statistics/daily-trend?startDate=2024-01-15&endDate=2024-03-05&page=2' }],
      [{ method: 'GET', path: '/api/v1/statistics/categories?range=all&entryType=INCOME' }],
    ])
  })

  it('rejects invalid or conflicting range query objects before HTTP', async () => {
    const request = vi.fn()
    const api = new StatisticsApi({ request } as never)

    await expect(api.summary({ startDate: '2024-01-01' })).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    await expect(api.summary({ month: '2024-09', rangeType: 'ALL' })).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    await expect(api.daily({ rangeType: 'ALL', page: 0 })).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(request).not.toHaveBeenCalled()
  })

  it.each(['2024-9', '0999-12', '10000-01', '2024-13'])('rejects invalid month %s before HTTP', async month => {
    const request = vi.fn()
    const api = new StatisticsApi({ request } as never)

    await expect(api.summary(month)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(request).not.toHaveBeenCalled()
  })
})
