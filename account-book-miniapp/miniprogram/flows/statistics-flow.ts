import type { EntryType } from '../types/catalog'
import type { AccountStatistics, DailyTrend, MonthlySummary, RankingStatistics } from '../types/statistics'

export interface StatisticsGateway {
  summary(month?: string): Promise<MonthlySummary>
  daily(month?: string): Promise<DailyTrend>
  categories(month?: string, entryType?: EntryType): Promise<RankingStatistics>
  accounts(month?: string): Promise<AccountStatistics>
  members(month?: string, entryType?: EntryType): Promise<RankingStatistics>
}

export class StatisticsFlow {
  constructor(private readonly api: StatisticsGateway) {}
  summary(month?: string) { return this.api.summary(month) }
  daily(month?: string) { return this.api.daily(month) }
  categories(month?: string, entryType?: EntryType) { return this.api.categories(month, entryType) }
  accounts(month?: string) { return this.api.accounts(month) }
  members(month?: string, entryType?: EntryType) { return this.api.members(month, entryType) }
}
