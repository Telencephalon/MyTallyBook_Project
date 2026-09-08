import type { EntryType } from './catalog'

export interface MonthlySummary {
  month: string
  income: string
  expense: string
  net: string
  entryCount: number
}

export interface DailyStatisticsItem extends Omit<MonthlySummary, 'month'> { date: string }
export interface DailyTrend { month: string; items: DailyStatisticsItem[] }

export interface RankingItem {
  id: number
  name: string
  amount: string
  percentage: string
  entryCount: number
}

export interface RankingStatistics {
  month: string
  entryType: EntryType
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

export interface AccountStatistics { month: string; items: AccountStatisticsItem[] }
