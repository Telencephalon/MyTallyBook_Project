import { describe, expect, it, vi } from 'vitest'

describe('runtime environment isolation', () => {
  it('creates a session store bound to the resolved environment identity', async () => {
    vi.resetModules()
    vi.doMock('../miniprogram/config/env', () => ({
      getApiEnvironment: () => ({ baseUrl: 'https://trial.example.com', timeoutMs: 10_000, envVersion: 'trial' }),
    }))
    const getRuntime = (await import('../miniprogram/runtime')).getRuntime
    const runtime = getRuntime()
    expect(runtime.session.getEnvironmentId()).toBe('trial|https://trial.example.com')
  })

  it('does not resolve the WeChat environment twice when the resolver omits envVersion', async () => {
    vi.resetModules()
    const getAccountInfoSync = vi.fn(() => ({ miniProgram: { envVersion: 'trial' } }))
    vi.stubGlobal('wx', { getAccountInfoSync, getStorageSync: vi.fn(), setStorageSync: vi.fn(), removeStorageSync: vi.fn(), request: vi.fn(), reLaunch: vi.fn() })
    vi.doMock('../miniprogram/config/env', () => ({
      getApiEnvironment: () => ({ baseUrl: 'https://trial.example.com', timeoutMs: 10_000 }),
    }))
    const getRuntime = (await import('../miniprogram/runtime')).getRuntime
    getRuntime()
    expect(getAccountInfoSync).toHaveBeenCalledTimes(0)
  })
})
