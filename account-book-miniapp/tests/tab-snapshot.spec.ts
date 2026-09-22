import { afterEach, describe, expect, it, vi } from 'vitest'
import { HttpClient, type RawRequestOptions } from '../miniprogram/services/http'
import { captureTabSnapshot, canReuseTabSnapshot, matchesTabSnapshot, invalidateTabSnapshots } from '../miniprogram/utils/tab-snapshot'

const session = { getRevision: () => 1, getUser: () => ({ userId: 1, ledgerId: 1, role: 'OWNER' }) } as never
const response = { statusCode: 200, header: {}, data: { code: 'OK', message: 'OK', requestId: 'fixture', timestamp: '2026-09-22', data: {} } }
afterEach(() => { vi.restoreAllMocks(); invalidateTabSnapshots() })

describe('tab snapshot freshness', () => {
  it('reuses only the same identity, role, query, data version and unexpired read', () => {
    let now = 1000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const stamp = captureTabSnapshot(session, 'month:2026-09')
    expect(canReuseTabSnapshot(stamp, session, 'month:2026-09')).toBe(true)
    expect(canReuseTabSnapshot(stamp, session, 'month:2026-08')).toBe(false)
    const member = { getRevision: () => 1, getUser: () => ({ userId: 1, ledgerId: 1, role: 'MEMBER' }) } as never
    expect(matchesTabSnapshot(stamp, member, 'month:2026-09')).toBe(false)
    now = 16000
    expect(canReuseTabSnapshot(stamp, session, 'month:2026-09')).toBe(false)
    expect(matchesTabSnapshot(stamp, session, 'month:2026-09')).toBe(true)
    now = 999
    expect(canReuseTabSnapshot(stamp, session, 'month:2026-09')).toBe(false)
  })

  it.each(['success', 'failure'] as const)('invalidates before an HTTP write and after its %s, including reads started during it', async outcome => {
    let request!: RawRequestOptions
    const client = new HttpClient({ environment: { baseUrl: 'http://127.0.0.1', timeoutMs: 1000 },
      getToken: () => 'token', onUnauthorized: () => {}, executor: options => { request = options } })
    const before = captureTabSnapshot(session, 'home')
    const writing = client.request({ method: 'PUT', path: '/api/v1/entries/1', body: { amount: '123.00' } }).catch(() => undefined)
    expect(canReuseTabSnapshot(before, session, 'home')).toBe(false)
    const during = captureTabSnapshot(session, 'home')
    if (outcome === 'success') request.success(response)
    else request.fail({ errMsg: 'timeout' })
    await writing
    expect(canReuseTabSnapshot(during, session, 'home')).toBe(false)
  })

  it('does not invalidate successful page data for ordinary reads', async () => {
    const client = new HttpClient({ environment: { baseUrl: 'http://127.0.0.1', timeoutMs: 1000 },
      getToken: () => 'token', onUnauthorized: () => {}, executor: options => options.success(response) })
    const stamp = captureTabSnapshot(session, 'home')
    await client.request({ method: 'GET', path: '/api/v1/users/me' })
    expect(canReuseTabSnapshot(stamp, session, 'home')).toBe(true)
  })
})
