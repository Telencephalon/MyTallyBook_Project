import { describe, expect, it } from 'vitest'
import { AuthApi } from '../../miniprogram/services/auth'
import { HttpClient, type RawRequestOptions } from '../../miniprogram/services/http'
import { LedgerApi } from '../../miniprogram/services/ledger'
import { UserApi } from '../../miniprogram/services/user'

function createApiHarness(responseData: unknown) {
  const requests: RawRequestOptions[] = []
  const http = new HttpClient({
    environment: { baseUrl: 'http://127.0.0.1:8080', timeoutMs: 10_000 },
    getToken: () => 'session-token',
    onUnauthorized: () => undefined,
    executor: options => {
      requests.push(options)
      options.success({
        statusCode: 200,
        data: {
          code: 'OK',
          message: 'success',
          data: responseData,
          requestId: 'request-id',
          timestamp: '2026-08-31T00:00:00Z',
        },
        header: { 'X-Request-Id': 'request-id' },
      })
    },
  })
  return { http, requests }
}

describe('AuthApi', () => {
  it('posts a WeChat code to the public login endpoint', async () => {
    const harness = createApiHarness({
      state: 'NEED_BOOTSTRAP', token: null, expiresAt: null,
    })

    await new AuthApi(harness.http).login('one-time-code')

    expect(harness.requests[0]).toMatchObject({
      method: 'POST',
      url: 'http://127.0.0.1:8080/api/v1/auth/wechat/login',
      data: { code: 'one-time-code' },
    })
    expect(harness.requests[0]?.header).not.toHaveProperty('Authorization')
  })

  it('posts a fresh code and key to the public bootstrap endpoint', async () => {
    const harness = createApiHarness({
      state: 'AUTHENTICATED', token: 'token', expiresAt: '2026-09-01T00:00:00Z',
    })

    await new AuthApi(harness.http).bootstrap('fresh-code', '12345678901234567890')

    expect(harness.requests[0]).toMatchObject({
      method: 'POST',
      url: 'http://127.0.0.1:8080/api/v1/auth/bootstrap',
      data: { code: 'fresh-code', bootstrapKey: '12345678901234567890' },
    })
    expect(harness.requests[0]?.header).not.toHaveProperty('Authorization')
  })

  it('posts an authenticated logout request', async () => {
    const harness = createApiHarness({ state: 'LOGGED_OUT' })

    await new AuthApi(harness.http).logout()

    expect(harness.requests[0]).toMatchObject({
      method: 'POST',
      url: 'http://127.0.0.1:8080/api/v1/auth/logout',
      header: { Authorization: 'Bearer session-token' },
    })
  })
})

describe('UserApi', () => {
  it('gets the authenticated current user', async () => {
    const harness = createApiHarness({ userId: 1 })

    await new UserApi(harness.http).getMe()

    expect(harness.requests[0]).toMatchObject({
      method: 'GET',
      url: 'http://127.0.0.1:8080/api/v1/users/me',
      header: { Authorization: 'Bearer session-token' },
    })
  })

  it('uses WeChat-supported PUT to update only the nickname', async () => {
    const harness = createApiHarness({ userId: 1, nickname: '新昵称' })

    await new UserApi(harness.http).updateNickname('新昵称')

    expect(harness.requests[0]).toMatchObject({
      method: 'PUT',
      url: 'http://127.0.0.1:8080/api/v1/users/me',
      data: { nickname: '新昵称' },
      header: { Authorization: 'Bearer session-token' },
    })
  })
})

describe('LedgerApi', () => {
  it('gets the authenticated fixed ledger', async () => {
    const harness = createApiHarness({ id: 1, name: '共享账本' })

    await new LedgerApi(harness.http).getFixedLedger()

    expect(harness.requests[0]).toMatchObject({
      method: 'GET',
      url: 'http://127.0.0.1:8080/api/v1/ledger',
      header: { Authorization: 'Bearer session-token' },
    })
  })
})
