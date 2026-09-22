import type { Ledger } from '../types/api'
import type { HttpClient } from './http'
import { requireMemberLimit } from '../utils/member-limit'

export class LedgerApi {
  constructor(private readonly http: HttpClient) {}

  async getFixedLedger(): Promise<Ledger> {
    const ledger = await this.http.request<Ledger>({
      method: 'GET',
      path: '/api/v1/ledger',
    })
    requireMemberLimit(ledger?.maxMembers)
    return ledger
  }
}
