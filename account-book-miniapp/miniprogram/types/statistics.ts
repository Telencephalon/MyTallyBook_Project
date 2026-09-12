import type { EntryType } from './catalog'
export type RankingDirection = EntryType | 'ALL'

export type StatisticsRangeType = 'MONTH' | 'ALL' | 'RANGE'

export interface StatisticsPeriodMeta {
  month: string | null
  rangeType: StatisticsRangeType
  startDate: string | null
  endDate: string | null
}

export interface StatisticsQuery {
  month?: string
  rangeType?: StatisticsRangeType
  startDate?: string
  endDate?: string
  page?: number
}

export interface MonthlySummary extends StatisticsPeriodMeta {
  income: string
  expense: string
  net: string
  entryCount: number
}

export interface DailyStatisticsItem {
  date: string
  income: string
  expense: string
  net: string
  entryCount: number
}
export interface DailyTrend extends StatisticsPeriodMeta {
  items: DailyStatisticsItem[]
  page: number
  pageSize: number
  totalDays: number
  totalPages: number
  hasNext: boolean
}

export interface RankingItem {
  id: number
  name: string
  amount: string
  percentage: string
  entryCount: number
}

export interface RankingStatistics extends StatisticsPeriodMeta {
  entryType: RankingDirection
  total: string
  items: RankingItem[]
}

export interface AccountStatisticsItem {
  id: number
  name: string
  income: string
  expense: string
  net: string
  entryCount: number
}

export interface AccountStatistics extends StatisticsPeriodMeta { items: AccountStatisticsItem[] }
