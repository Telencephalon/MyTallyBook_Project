import { describe, expect, it } from 'vitest'
import { resolveApiEnvironment } from '../../miniprogram/config/env'

function captureError(action: () => unknown): unknown {
  try {
    action()
  } catch (error) {
    return error
  }
  throw new Error('Expected action to throw')
}

describe('resolveApiEnvironment', () => {
  it('uses the loopback backend only for develop builds', () => {
    expect(resolveApiEnvironment('develop')).toEqual({
      baseUrl: 'http://127.0.0.1:7631',
      timeoutMs: 10_000,
      envVersion: 'develop',
    })
  })

  it.each(['trial', 'release'])('blocks %s when its HTTPS domain is absent', envVersion => {
    expect(captureError(() => resolveApiEnvironment(envVersion, {}))).toMatchObject({
      code: 'API_DOMAIN_NOT_CONFIGURED',
    })
  })

  it('selects distinct HTTPS addresses for trial and release', () => {
    const addresses = {
      trialBaseUrl: 'https://trial-api.mytallybook.example',
      releaseBaseUrl: 'https://api.mytallybook.example',
    }
    expect(resolveApiEnvironment('trial', addresses)).toEqual({
      baseUrl: addresses.trialBaseUrl,
      timeoutMs: 10_000,
      envVersion: 'trial',
    })
    expect(resolveApiEnvironment('release', addresses)).toEqual({
      baseUrl: 'https://api.mytallybook.example',
      timeoutMs: 10_000,
      envVersion: 'release',
    })
  })

  it.each([
    'http://api.example.com',
    'https://117.72.101.42',
    'https://localhost',
    'https://127.1',
    'https://2130706433',
    'https://0x7f000001',
    'https://127.0.0.1.',
    'https://[::1]',
    'https://[::1]:443',
    'https://user:pass@api.example.com',
    'https://api.example.com/path',
    'https://api.example.com?token=secret',
    'https://api.example.com#fragment',
  ])('rejects unsafe deployed address %s', deployedBaseUrl => {
    expect(captureError(() => resolveApiEnvironment('release', { releaseBaseUrl: deployedBaseUrl }))).toMatchObject({
      code: 'API_DOMAIN_INVALID',
    })
  })

  it('rejects unknown environment versions', () => {
    expect(captureError(() => resolveApiEnvironment('preview', {}))).toMatchObject({
      code: 'API_ENVIRONMENT_UNKNOWN',
    })
  })
})
