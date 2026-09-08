import type { ApiEnvironment } from '../config/env'
import type { ApiErrorResponse, ApiResponse } from '../types/api'
import { AppError } from '../types/error'

export interface RawResponse {
  statusCode: number
  data: unknown
  header: Record<string, string>
}

export interface RawRequestOptions {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: RequestData
  header: Record<string, string>
  timeout: number
  success(response: RawResponse): void
  fail(error: { errMsg: string }): void
}

export type RequestExecutor = (options: RawRequestOptions) => void

export type RequestData = string | WechatMiniprogram.IAnyObject | ArrayBuffer

export interface HttpRequestOptions<TBody extends RequestData> {
  method: RawRequestOptions['method']
  path: `/api/v1/${string}`
  body?: TBody
  authenticated?: boolean
}

interface HttpClientOptions {
  environment: ApiEnvironment
  getToken(): string | null
  onUnauthorized(): void
  executor: RequestExecutor
}

export function createRequestId(now = Date.now(), random = Math.random()): string {
  return `mp-${now.toString(36)}-${Math.floor(random * 0x100000000).toString(36)}`
}

export class HttpClient {
  private unauthorizedHandled = false

  constructor(private readonly options: HttpClientOptions) {}

  request<TData, TBody extends RequestData = WechatMiniprogram.IAnyObject>(
    request: HttpRequestOptions<TBody>,
  ): Promise<TData> {
    const requestId = createRequestId()
    const authenticated = request.authenticated ?? true
    const requestToken = authenticated ? this.options.getToken() : null
    const header: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Request-Id': requestId,
    }

    if (authenticated) {
      if (!requestToken) {
        this.handleUnauthorized()
        return Promise.reject(new AppError(
          'HTTP',
          'AUTHENTICATION_REQUIRED',
          '请重新登录',
          requestId,
          401,
        ))
      }
      header.Authorization = `Bearer ${requestToken}`
    }

    return new Promise<TData>((resolve, reject) => {
      const rawRequest: RawRequestOptions = {
        url: `${this.options.environment.baseUrl}${request.path}`,
        method: request.method,
        header,
        timeout: this.options.environment.timeoutMs,
        success: response => {
          try {
            resolve(this.parseResponse<TData>(response, requestId, authenticated, requestToken))
          } catch (error) {
            reject(error)
          }
        },
        fail: error => reject(this.normalizeNetworkError(error.errMsg, requestId)),
      }
      if (request.body !== undefined) {
        rawRequest.data = request.body
      }

      try {
        this.options.executor(rawRequest)
      } catch {
        reject(new AppError(
          'NETWORK',
          'NETWORK_ERROR',
          '网络连接失败，请检查网络后重试',
          requestId,
        ))
      }
    })
  }

  private parseResponse<TData>(
    response: RawResponse,
    clientRequestId: string,
    authenticated: boolean,
    requestToken: string | null,
  ): TData {
    const headerRequestId = this.readRequestIdHeader(response.header)
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (!this.isSuccessResponse<TData>(response.data)) {
        throw new AppError(
          'INVALID_RESPONSE',
          'INVALID_SERVER_RESPONSE',
          '服务器响应格式不正确，请稍后重试',
          headerRequestId ?? clientRequestId,
          response.statusCode,
        )
      }
      this.unauthorizedHandled = false
      return response.data.data
    }

    if (authenticated && response.statusCode === 401 && requestToken === this.options.getToken()) {
      this.handleUnauthorized()
    }

    if (this.isErrorResponse(response.data)) {
      throw new AppError(
        'HTTP',
        response.data.code,
        response.data.message,
        response.data.requestId,
        response.statusCode,
      )
    }

    throw new AppError(
      'HTTP',
      'HTTP_ERROR',
      response.statusCode >= 500 ? '服务暂不可用，请稍后重试' : '请求失败，请重试',
      headerRequestId ?? clientRequestId,
      response.statusCode,
    )
  }

  private normalizeNetworkError(errMsg: string, requestId: string): AppError {
    if (errMsg.toLowerCase().includes('timeout')) {
      return new AppError(
        'TIMEOUT',
        'REQUEST_TIMEOUT',
        '请求超时，请稍后重试',
        requestId,
      )
    }
    return new AppError(
      'NETWORK',
      'NETWORK_ERROR',
      '网络连接失败，请检查网络后重试',
      requestId,
    )
  }

  private handleUnauthorized(): void {
    if (this.unauthorizedHandled) {
      return
    }
    this.unauthorizedHandled = true
    this.options.onUnauthorized()
  }

  private readRequestIdHeader(header: Record<string, string>): string | undefined {
    const match = Object.entries(header).find(([name]) => name.toLowerCase() === 'x-request-id')
    return match?.[1]
  }

  private isSuccessResponse<TData>(value: unknown): value is ApiResponse<TData> {
    return this.isRecord(value)
      && value.code === 'OK'
      && typeof value.message === 'string'
      && Object.prototype.hasOwnProperty.call(value, 'data')
      && typeof value.requestId === 'string'
      && typeof value.timestamp === 'string'
  }

  private isErrorResponse(value: unknown): value is ApiErrorResponse {
    return this.isRecord(value)
      && typeof value.code === 'string'
      && typeof value.message === 'string'
      && typeof value.requestId === 'string'
      && typeof value.timestamp === 'string'
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null
  }
}

export const wechatRequestExecutor: RequestExecutor = options => {
  wx.request({
    url: options.url,
    method: options.method,
    data: options.data,
    header: options.header,
    timeout: options.timeout,
    success: response => options.success({
      statusCode: response.statusCode,
      data: response.data,
      header: response.header as Record<string, string>,
    }),
    fail: error => options.fail({ errMsg: error.errMsg }),
  })
}
