import type { UserProfile } from '../types/api'
import type { HttpClient } from './http'

export class UserApi {
  constructor(private readonly http: HttpClient) {}

  getMe(): Promise<UserProfile> {
    return this.http.request<UserProfile>({
      method: 'GET',
      path: '/api/v1/users/me',
    })
  }

  updateNickname(nickname: string): Promise<UserProfile> {
    return this.http.request<UserProfile>({
      method: 'PUT',
      path: '/api/v1/users/me',
      body: { nickname },
    })
  }
}
