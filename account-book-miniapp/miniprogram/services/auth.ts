import type { AuthStateData } from '../types/api'
import type { HttpClient } from './http'

export class AuthApi {
  constructor(private readonly http: HttpClient) {}

  login(code: string): Promise<AuthStateData> {
    return this.http.request<AuthStateData>({
      method: 'POST',
      path: '/api/v1/auth/wechat/login',
      body: { code },
      authenticated: false,
    })
  }

  bootstrap(code: string, bootstrapKey: string): Promise<AuthStateData> {
    return this.http.request<AuthStateData>({
      method: 'POST',
      path: '/api/v1/auth/bootstrap',
      body: { code, bootstrapKey },
      authenticated: false,
    })
  }

  logout(): Promise<{ state: string }> {
    return this.http.request<{ state: string }>({
      method: 'POST',
      path: '/api/v1/auth/logout',
    })
  }
}
