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
    })
  })

  it.each(['trial', 'release'])('blocks %s when the HTTPS domain is absent', envVersion => {
    expect(captureError(() => resolveApiEnvironment(envVersion))).toMatchObject({
      code: 'API_DOMAIN_NOT_CONFIGURED',
    })
  })

  it('accepts an HTTPS domain for trial and release', () => {
    expect(resolveApiEnvironment('release', 'https://api.mytallybook.example')).toEqual({
      baseUrl: 'https://api.mytallybook.example',
      timeoutMs: 10_000,
    })
  })

  it.each([
    'http://api.example.com',
    'https://117.72.101.42',
    'https://localhost',
  ])('rejects unsafe deployed address %s', deployedBaseUrl => {
    expect(captureError(() => resolveApiEnvironment('release', deployedBaseUrl))).toMatchObject({
      code: 'API_DOMAIN_INVALID',
    })
  })
})
