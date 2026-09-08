export type AppErrorKind = 'HTTP' | 'NETWORK' | 'TIMEOUT' | 'CONFIG' | 'INVALID_RESPONSE'

export class AppError extends Error {
  constructor(
    public readonly kind: AppErrorKind,
    public readonly code: string,
    message: string,
    public readonly requestId?: string,
    public readonly statusCode?: number,
  ) {
    super(message)
    this.name = 'AppError'
  }
}
