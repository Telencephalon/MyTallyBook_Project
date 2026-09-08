import type { Ledger } from '../types/api'
import type { HttpClient } from './http'

export class LedgerApi {
  constructor(private readonly http: HttpClient) {}

  getFixedLedger(): Promise<Ledger> {
    return this.http.request<Ledger>({
      method: 'GET',
      path: '/api/v1/ledger',
    })
  }
}
