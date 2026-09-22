import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RawRequestOptions } from '../miniprogram/services/http'
import { harness, ledger, owner } from './invite-member-harness'

afterEach(() => vi.unstubAllGlobals())

async function pendingContext() {
  const h = await harness()
  const pending: RawRequestOptions[] = []
  h.respond(request => { pending.push(request); return true })
  return {
    ...h,
    pending,
    replyContext(requests: RawRequestOptions[], role: 'OWNER' | 'MEMBER' = 'OWNER') {
      for (const request of requests) {
        h.reply(request, request.url.endsWith('/users/me') ? { ...owner, role } : ledger)
      }
    },
  }
}

const resultOf = (operation: Promise<void>) => operation.then(() => 'ready', error => error)

describe('opt-in pending session context reuse', () => {
  it('shares concurrent read requests and returns a valid context to both callers', async () => {
    const h = await pendingContext()
    const first = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const second = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const count = h.pending.length
    h.replyContext(h.pending)

    expect(await Promise.all([first, second])).toEqual(['ready', 'ready'])
    expect(count).toBe(2)
    expect(h.runtime.session.getUser()).toEqual(owner)
    expect(h.runtime.session.getLedger()).toEqual(ledger)
  })

  it('starts a fresh permission read after a previous read completes', async () => {
    const h = await pendingContext()
    const first = h.runtime.flow.refreshContext({ reusePending: true })
    h.replyContext(h.pending)
    await first

    const next = h.runtime.flow.refreshContext({ reusePending: true })
    const freshRequests = h.pending.slice(2)
    h.replyContext(freshRequests, 'MEMBER')
    await next

    expect(freshRequests).toHaveLength(2)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })

  it('forces a newer context after mutation and lets subsequent reads join only that newer request', async () => {
    const h = await pendingContext()
    const old = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const newer = resultOf(h.runtime.flow.refreshContext())
    const requestCount = h.pending.length

    // An obsolete completion must not clear the newer pending request.
    h.replyContext(h.pending.slice(0, 2))
    const oldResult = await old
    const joined = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const joinedRequestCount = h.pending.length
    h.replyContext(h.pending.slice(2), 'MEMBER')

    expect(oldResult).toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(await Promise.all([newer, joined])).toEqual(['ready', 'ready'])
    expect(requestCount).toBe(4)
    expect(joinedRequestCount).toBe(4)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })

  it.each(['existing-test-session', 'second-person-session'])('does not reuse requests from an earlier identity epoch with token %s', async token => {
    const h = await pendingContext()
    const old = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token, expiresAt: '2099-01-01T00:00:00Z' })
    const newer = h.runtime.flow.refreshContext({ reusePending: true })
    const freshRequests = h.pending.slice(2)
    h.replyContext(freshRequests, 'MEMBER')
    await newer
    h.replyContext(h.pending.slice(0, 2))

    expect(await old).toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(freshRequests).toHaveLength(2)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })

  it('releases a failed shared read so the next read retries the server', async () => {
    const h = await pendingContext()
    const first = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const second = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const failedCount = h.pending.length
    for (const request of h.pending) request.fail({ errMsg: 'request:fail offline' })
    const failures = await Promise.all([first, second])

    const retry = h.runtime.flow.refreshContext({ reusePending: true })
    const retryRequests = h.pending.slice(failedCount)
    h.replyContext(retryRequests)
    await retry

    expect(failedCount).toBe(2)
    expect(failures).toEqual([expect.objectContaining({ code: 'NETWORK_ERROR' }), expect.objectContaining({ code: 'NETWORK_ERROR' })])
    expect(retryRequests).toHaveLength(2)
    expect(h.runtime.session.getUser()).toEqual(owner)
  })

  it('invalidates context guards when a fresh read starts, while joining an existing read preserves its guard', async () => {
    const h = await pendingContext()
    const beforeRead = h.runtime.flow.captureContextGuard()
    const first = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const duringRead = h.runtime.flow.captureContextGuard()
    const second = resultOf(h.runtime.flow.refreshContext({ reusePending: true }))
    const oldGuardActive = beforeRead()
    const pendingGuardActive = duringRead()
    h.replyContext(h.pending)
    const initialResults = await Promise.all([first, second])

    const settledCount = h.pending.length
    const next = h.runtime.flow.refreshContext({ reusePending: true })
    const previousGuardActive = duringRead()
    h.replyContext(h.pending.slice(settledCount))
    await next

    expect(initialResults).toEqual(['ready', 'ready'])
    expect(oldGuardActive).toBe(false)
    expect(pendingGuardActive).toBe(true)
    expect(previousGuardActive).toBe(false)
  })
})
