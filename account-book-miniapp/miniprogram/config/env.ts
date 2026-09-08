import { AppError } from '../types/error'

const DEPLOYED_API_BASE_URL = ''
const TIMEOUT_MS = 10_000

export interface ApiEnvironment {
  baseUrl: string
  timeoutMs: number
}

export function resolveApiEnvironment(
  envVersion: string,
  deployedBaseUrl = DEPLOYED_API_BASE_URL,
): ApiEnvironment {
  if (envVersion === 'develop') {
    return {
      baseUrl: 'http://127.0.0.1:7631',
      timeoutMs: TIMEOUT_MS,
    }
  }

  if (!deployedBaseUrl) {
    throw new AppError(
      'CONFIG',
      'API_DOMAIN_NOT_CONFIGURED',
      '体验版或正式版 API 域名尚未配置',
    )
  }

  const hostMatch = /^https:\/\/([^/:]+)(?::443)?\/?$/.exec(deployedBaseUrl)
  const host = hostMatch?.[1] ?? ''
  if (!host || host === 'localhost' || /^\d{1,3}(?:\.\d{1,3}){3}$/.test(host)) {
    throw new AppError(
      'CONFIG',
      'API_DOMAIN_INVALID',
      'API 地址必须是备案后的 HTTPS 域名',
    )
  }

  return {
    baseUrl: deployedBaseUrl.replace(/\/$/, ''),
    timeoutMs: TIMEOUT_MS,
  }
}

export function getApiEnvironment(): ApiEnvironment {
  const envVersion = wx.getAccountInfoSync().miniProgram.envVersion
  return resolveApiEnvironment(envVersion)
}
