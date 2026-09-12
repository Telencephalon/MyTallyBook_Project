import type { AccountStatistics, DailyTrend, MonthlySummary, RankingDirection, RankingStatistics, StatisticsQuery } from '../types/statistics'

type QueryInput = string | StatisticsQuery | undefined

export interface StatisticsGateway {
  summary(query?: QueryInput): Promise<MonthlySummary>
  daily(query?: QueryInput): Promise<DailyTrend>
  categories(query?: QueryInput, entryType?: RankingDirection): Promise<RankingStatistics>
  accounts(query?: QueryInput): Promise<AccountStatistics>
  members(query?: QueryInput, entryType?: RankingDirection): Promise<RankingStatistics>
}

export class StatisticsFlow {
  constructor(private readonly api: StatisticsGateway) {}
  summary(query?: QueryInput) { return this.api.summary(query) }
  daily(query?: QueryInput) { return this.api.daily(query) }
  categories(query?: QueryInput, entryType?: RankingDirection) { return this.api.categories(query, entryType) }
  accounts(query?: QueryInput) { return this.api.accounts(query) }
  members(query?: QueryInput, entryType?: RankingDirection) { return this.api.members(query, entryType) }
}
