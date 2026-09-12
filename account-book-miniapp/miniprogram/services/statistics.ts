import { AppError } from '../types/error'
import type { AccountStatistics, DailyTrend, MonthlySummary, RankingStatistics, StatisticsQuery, RankingDirection } from '../types/statistics'
import type { HttpClient } from './http'

const MONTH = /^[1-9]\d{3}-(0[1-9]|1[0-2])$/
const DATE = /^[1-9]\d{3}-\d{2}-\d{2}$/

type QueryInput = string | StatisticsQuery | undefined

function invalidRange(): never {
  throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效统计范围')
}

function validMonth(month: string) {
  if (!MONTH.test(month)) {
    throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '请选择有效月份')
  }
}

function validDate(value: string): boolean {
  if (!DATE.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value &&
    value >= '1000-01-01' && value <= '9999-12-31'
}

function normalized(input?: QueryInput): StatisticsQuery {
  if (typeof input === 'string') {
    validMonth(input)
    return { month: input }
  }
  return input ?? {}
}

function queryParts(input?: QueryInput, includePage = false): string[] {
  const query = normalized(input)
  const parts: string[] = []
  const hasMonth = query.month !== undefined
  const hasRange = query.rangeType !== undefined
  const hasStart = query.startDate !== undefined
  const hasEnd = query.endDate !== undefined
  const hasDates = hasStart || hasEnd
  const selectorCount = (hasMonth ? 1 : 0) + (hasRange ? 1 : 0) + (hasDates ? 1 : 0)
  if (selectorCount > 1) invalidRange()
  if (hasMonth) {
    validMonth(query.month!)
    parts.push(`month=${query.month}`)
  } else if (hasRange) {
    if (query.rangeType !== 'ALL') invalidRange()
    parts.push('range=all')
  } else if (hasDates) {
    if (!hasStart || !hasEnd) invalidRange()
    if (!validDate(query.startDate!) || !validDate(query.endDate!) || query.endDate! < query.startDate!) invalidRange()
    parts.push(`startDate=${query.startDate}`, `endDate=${query.endDate}`)
  }
  if (query.page !== undefined) {
    if (!includePage || !Number.isInteger(query.page) || query.page <= 0) invalidRange()
    parts.push(`page=${query.page}`)
  }
  return parts
}

function rangeQuery(input?: QueryInput, includePage = false): string {
  const parts = queryParts(input, includePage)
  return parts.length ? `?${parts.join('&')}` : ''
}

function rankingQuery(input?: QueryInput, entryType?: RankingDirection): string {
  const parts = queryParts(input)
  if (entryType !== undefined) parts.push(`entryType=${entryType}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export class StatisticsApi {
  constructor(private readonly http: HttpClient) {}

  async summary(query?: QueryInput): Promise<MonthlySummary> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/monthly-summary${rangeQuery(query)}` })
  }

  async daily(query?: QueryInput): Promise<DailyTrend> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/daily-trend${rangeQuery(query, true)}` })
  }

  async categories(query?: QueryInput, entryType?: RankingDirection): Promise<RankingStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/categories${rankingQuery(query, entryType)}` })
  }

  async accounts(query?: QueryInput): Promise<AccountStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/accounts${rangeQuery(query)}` })
  }

  async members(query?: QueryInput, entryType?: RankingDirection): Promise<RankingStatistics> {
    return this.http.request({ method: 'GET', path: `/api/v1/statistics/members${rankingQuery(query, entryType)}` })
  }
}
