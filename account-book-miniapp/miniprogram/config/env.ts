import { AppError } from '../types/error'

const DEPLOYED_API_ADDRESSES = { trialBaseUrl: '', releaseBaseUrl: '' }
const TIMEOUT_MS = 10_000

export interface ApiEnvironment {
  baseUrl: string
  timeoutMs: number
  envVersion?: string
}

export interface ApiEnvironmentAddresses {
  trialBaseUrl?: string
  releaseBaseUrl?: string
}

export function resolveApiEnvironment(
  envVersion: string,
  addresses: ApiEnvironmentAddresses = DEPLOYED_API_ADDRESSES,
): ApiEnvironment {
  if (envVersion === 'develop') {
    return {
      baseUrl: 'http://127.0.0.1:7631',
      timeoutMs: TIMEOUT_MS,
      envVersion,
    }
  }

  if (envVersion !== 'trial' && envVersion !== 'release') {
    throw new AppError('CONFIG', 'API_ENVIRONMENT_UNKNOWN', '未知的小程序运行环境')
  }

  const deployedBaseUrl = envVersion === 'trial' ? addresses.trialBaseUrl : addresses.releaseBaseUrl
  if (!deployedBaseUrl) {
    throw new AppError(
      'CONFIG',
      'API_DOMAIN_NOT_CONFIGURED',
      '体验版或正式版 API 域名尚未配置',
    )
  }

  const match = /^([a-z]+):\/\/([^/?#]+)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/.exec(deployedBaseUrl)
  const protocol = match?.[1]?.toLowerCase() ?? ''
  const authority = match?.[2] ?? ''
  const host = authority.split(':')[0]?.toLowerCase() ?? ''
  const hasCredentials = authority.includes('@')
  const port = authority.includes(':') ? authority.slice(authority.lastIndexOf(':') + 1) : ''
  const isIpv4 = /^(?:\d{1,3}\.)+\d{1,3}$/.test(host)
  const isIpv6 = host.includes(':')
  const isDecimalIp = /^\d+$/.test(host)
  const isHexIp = /^0x[0-9a-f]+$/i.test(host)
  const bracketedIpv6 = authority.startsWith('[')
  if (protocol !== 'https' || !host || host === 'localhost' || host.endsWith('.') || host.endsWith('.localhost') || isIpv4 || isIpv6 || isDecimalIp || isHexIp || bracketedIpv6 || hasCredentials || (port && port !== '443') || (match?.[3] && match[3] !== '/') || match?.[4] || match?.[5]) {
    throw new AppError(
      'CONFIG',
      'API_DOMAIN_INVALID',
      'API 地址必须是备案后的 HTTPS 域名',
    )
  }

  return {
      baseUrl: deployedBaseUrl.replace(/\/$/, ''),
      timeoutMs: TIMEOUT_MS,
      envVersion,
  }
}

export function getApiEnvironment(): ApiEnvironment {
  const envVersion = wx.getAccountInfoSync().miniProgram.envVersion
  return resolveApiEnvironment(envVersion, DEPLOYED_API_ADDRESSES)
}
