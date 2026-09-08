import type { EntryType } from '../types/catalog'
import { AppError } from '../types/error'
import type { AccountStatistics, DailyTrend, MonthlySummary, RankingStatistics } from '../types/statistics'
import type { HttpClient } from './http'

const MONTH = /^[1-9]\d{3}-(0[1-9]|1[0-2])$/

function monthQuery(month?: string): string {
  if (month === undefined) return ''
  if (!MONTH.test(month)) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效月份')
  }
  return `?month=${month}`
}

function rankingQuery(month?: string, entryType?: EntryType): string {
  const parts: string[] = []
  if (month !== undefined) {
    monthQuery(month)
    parts.push(`month=${month}`)
  }
  if (entryType !== undefined) parts.push(`entryType=${entryType}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export class StatisticsApi {
  constructor(private readonly http: HttpClient) {}

  async summary(month?: string): Promise<MonthlySummary> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/monthly-summary${monthQuery(month)}` })
  }

  async daily(month?: string): Promise<DailyTrend> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/daily-trend${monthQuery(month)}` })
  }

  async categories(month?: string, entryType?: EntryType): Promise<RankingStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/categories${rankingQuery(month, entryType)}` })
  }

  async accounts(month?: string): Promise<AccountStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/accounts${monthQuery(month)}` })
  }

  async members(month?: string, entryType?: EntryType): Promise<RankingStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/members${rankingQuery(month, entryType)}` })
  }
}
