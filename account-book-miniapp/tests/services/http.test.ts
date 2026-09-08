import { describe, expect, it } from 'vitest'
import {
  HttpClient,
  createRequestId,
  type RawRequestOptions,
  type RequestExecutor,
} from '../../miniprogram/services/http'

function successEnvelope<T>(data: T, requestId = 'server-request-id') {
  return {
    statusCode: 200,
    data: {
      code: 'OK',
      message: 'success',
      data,
      requestId,
      timestamp: '2026-08-31T00:00:00Z',
    },
    header: { 'X-Request-Id': requestId },
  }
}

function createClient(
  executor: RequestExecutor,
  options: {
    token?: string | null
    onUnauthorized?: () => void
  } = {},
): HttpClient {
  return new HttpClient({
    environment: {
      baseUrl: 'http://127.0.0.1:8080',
      timeoutMs: 10_000,
    },
    getToken: () => options.token === undefined ? 'session-token' : options.token,
    onUnauthorized: options.onUnauthorized ?? (() => undefined),
    executor,
  })
}

describe('createRequestId', () => {
  it('creates a backend-safe identifier within 64 characters', () => {
    expect(createRequestId(1_000, 0.5)).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
  })
})

describe('HttpClient', () => {
  it('returns typed data and injects the Token, request ID, JSON header, and timeout', async () => {
    const captured: RawRequestOptions[] = []
    const client = createClient(options => {
      captured.push(options)
      options.success(successEnvelope({ id: 1 }))
    })

    const result = await client.request<{ id: number }>({
      method: 'GET',
      path: '/api/v1/users/me',
    })

    expect(result).toEqual({ id: 1 })
    expect(captured[0]).toMatchObject({
      url: 'http://127.0.0.1:8080/api/v1/users/me',
      method: 'GET',
      timeout: 10_000,
      header: {
        Authorization: 'Bearer session-token',
        'Content-Type': 'application/json',
      },
    })
    expect(captured[0]?.header['X-Request-Id']).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
  })

  it('omits Authorization for a public POST and forwards the JSON body', async () => {
    const captured: RawRequestOptions[] = []
    const client = createClient(options => {
      captured.push(options)
      options.success(successEnvelope({ state: 'NEED_BOOTSTRAP' }))
    })

    await client.request({
      method: 'POST',
      path: '/api/v1/auth/wechat/login',
      body: { code: 'one-time-code' },
      authenticated: false,
    })

    expect(captured[0]?.header).not.toHaveProperty('Authorization')
    expect(captured[0]?.data).toEqual({ code: 'one-time-code' })
  })

  it('rejects a protected request with no Token before contacting the network', async () => {
    let networkCalled = false
    const client = createClient(() => {
      networkCalled = true
    }, { token: null })

    await expect(client.request({ method: 'GET', path: '/api/v1/users/me' }))
      .rejects.toMatchObject({
        kind: 'HTTP',
        code: 'AUTHENTICATION_REQUIRED',
        statusCode: 401,
      })
    expect(networkCalled).toBe(false)
  })

  it('preserves a structured business error and its request ID', async () => {
    const client = createClient(options => {
      options.success({
        statusCode: 403,
        data: {
          code: 'USER_DISABLED',
          message: '用户已停用',
          requestId: 'business-request-id',
          timestamp: '2026-08-31T00:00:00Z',
        },
        header: { 'X-Request-Id': 'header-request-id' },
      })
    })

    await expect(client.request({ method: 'GET', path: '/api/v1/users/me' }))
      .rejects.toMatchObject({
        kind: 'HTTP',
        code: 'USER_DISABLED',
        message: '用户已停用',
        requestId: 'business-request-id',
        statusCode: 403,
      })
  })

  it('uses the response header request ID when an HTTP error body is invalid', async () => {
    const client = createClient(options => {
      options.success({
        statusCode: 500,
        data: '<html>gateway error</html>',
        header: { 'x-request-id': 'header-request-id' },
      })
    })

    await expect(client.request({ method: 'GET', path: '/api/v1/ledger' }))
      .rejects.toMatchObject({
        kind: 'HTTP',
        code: 'HTTP_ERROR',
        message: '服务暂不可用，请稍后重试',
        requestId: 'header-request-id',
        statusCode: 500,
      })
  })

  it('rejects a 2xx response that does not match the success envelope', async () => {
    const client = createClient(options => {
      options.success({
        statusCode: 200,
        data: { code: 'OK', message: 'success' },
        header: { 'X-Request-Id': 'header-request-id' },
      })
    })

    await expect(client.request({ method: 'GET', path: '/api/v1/ledger' }))
      .rejects.toMatchObject({
        kind: 'INVALID_RESPONSE',
        code: 'INVALID_SERVER_RESPONSE',
        requestId: 'header-request-id',
      })
  })

  it.each([
    ['request:fail timeout', 'TIMEOUT', 'REQUEST_TIMEOUT'],
    ['request:fail network unavailable', 'NETWORK', 'NETWORK_ERROR'],
  ] as const)('normalizes native failure %s', async (errMsg, kind, code) => {
    const client = createClient(options => options.fail({ errMsg }))

    await expect(client.request({ method: 'GET', path: '/api/v1/ledger' }))
      .rejects.toMatchObject({ kind, code })
  })

  it('handles concurrent authenticated 401 responses only once', async () => {
    const pending: RawRequestOptions[] = []
    let unauthorizedCount = 0
    const client = createClient(options => pending.push(options), {
      onUnauthorized: () => { unauthorizedCount += 1 },
    })

    const first = client.request({ method: 'GET', path: '/api/v1/users/me' })
    const second = client.request({ method: 'GET', path: '/api/v1/ledger' })
    for (const request of pending) {
      request.success({
        statusCode: 401,
        data: {
          code: 'AUTHENTICATION_REQUIRED',
          message: '请重新登录',
          requestId: 'request-401',
          timestamp: '2026-08-31T00:00:00Z',
        },
        header: { 'X-Request-Id': 'request-401' },
      })
    }

    const settled = await Promise.allSettled([first, second])
    expect(settled.map(result => result.status)).toEqual(['rejected', 'rejected'])
    expect(unauthorizedCount).toBe(1)
  })

  it('resets the unauthorized gate after a later successful response', async () => {
    let responseMode: 'unauthorized' | 'success' = 'unauthorized'
    let unauthorizedCount = 0
    const client = createClient(options => {
      if (responseMode === 'unauthorized') {
        options.success({
          statusCode: 401,
          data: {
            code: 'AUTHENTICATION_REQUIRED',
            message: '请重新登录',
            requestId: 'request-401',
            timestamp: '2026-08-31T00:00:00Z',
          },
          header: { 'X-Request-Id': 'request-401' },
        })
      } else {
        options.success(successEnvelope({ ok: true }))
      }
    }, {
      onUnauthorized: () => { unauthorizedCount += 1 },
    })

    await expect(client.request({ method: 'GET', path: '/api/v1/users/me' })).rejects.toBeDefined()
    responseMode = 'success'
    await expect(client.request({ method: 'GET', path: '/api/v1/users/me' })).resolves.toEqual({ ok: true })
    responseMode = 'unauthorized'
    await expect(client.request({ method: 'GET', path: '/api/v1/users/me' })).rejects.toBeDefined()

    expect(unauthorizedCount).toBe(2)
  })

  it('does not retain request data or the Token on normalized errors', async () => {
    const client = createClient(options => {
      options.success({
        statusCode: 500,
        data: { internal: 'do-not-retain' },
        header: {},
      })
    })

    const error = await client.request({
      method: 'POST',
      path: '/api/v1/auth/logout',
      body: { privateValue: 'do-not-retain' },
    }).catch(reason => reason as object)

    expect(error).not.toHaveProperty('data')
    expect(error).not.toHaveProperty('body')
    expect(error).not.toHaveProperty('token')
    expect(JSON.stringify(error)).not.toContain('session-token')
    expect(JSON.stringify(error)).not.toContain('do-not-retain')
  })
})
