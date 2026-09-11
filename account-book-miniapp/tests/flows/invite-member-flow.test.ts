import { afterEach, describe, expect, it, vi } from 'vitest'
import { harness, inviteToken, owner } from '../invite-member-harness'

afterEach(() => vi.unstubAllGlobals())
describe('real invitation/session/member flows', () => {
  it('old authenticated 401 does not clear a newer authenticated identity', async () => {
    const h = await harness()
    const pending: import('../../miniprogram/services/http').RawRequestOptions[] = []
    h.respond(r => { pending.push(r); return true })
    const oldRefresh = h.runtime.flow.refreshContext()
    h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'second-person-session', expiresAt: '2099-01-01T00:00:00Z' })
    h.setUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER' })
    h.reply(pending[0]!, null, 401, 'AUTHENTICATION_REQUIRED')
    h.reply(pending[1]!, null, 401, 'AUTHENTICATION_REQUIRED')
    await expect(oldRefresh).rejects.toMatchObject({ statusCode: 401 })
    expect(h.runtime.session.getToken()).toBe('second-person-session')
    expect(h.runtime.session.getUser()?.userId).toBe(2)
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
  })
  it.each(['clear', 'switch'])('context in flight cannot revive or pollute session after %s', async change => {
    const h = await harness()
    const pending: import('../../miniprogram/services/http').RawRequestOptions[] = []
    h.respond(r => { pending.push(r); return true })
    const refresh = h.runtime.flow.refreshContext()
    if (change === 'clear') h.runtime.session.clear()
    else {
      h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'second-person-session', expiresAt: '2099-01-01T00:00:00Z' })
      h.setUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER' })
    }
    for (const r of pending) h.reply(r, r.url.endsWith('/users/me') ? owner : { id: 1, name: '旧账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 })
    await expect(refresh).rejects.toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(h.runtime.session.getUser()?.userId ?? null).toBe(change === 'clear' ? null : 2)
    expect(h.runtime.session.getToken()).toBe(change === 'clear' ? null : 'second-person-session')
  })
  it('older concurrent context refresh cannot overwrite newer demotion for the same token', async () => {
    const h = await harness()
    const pending: import('../../miniprogram/services/http').RawRequestOptions[] = []
    h.respond(r => { pending.push(r); return true })
    const old = h.runtime.flow.refreshContext()
    const newer = h.runtime.flow.refreshContext()
    h.reply(pending[2]!, { ...owner, role: 'MEMBER' })
    h.reply(pending[3]!, { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 })
    await newer
    h.reply(pending[0]!, owner)
    h.reply(pending[1]!, { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 })
    await expect(old).rejects.toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })
  it.each([[409, 'ALREADY_MEMBER'], [409, 'INVITE_USED'], [401, 'AUTHENTICATION_REQUIRED'], [410, 'INVITE_EXPIRED'], [500, 'INTERNAL_ERROR']])('anonymous accept failure %s/%s preserves existing session without retry', async (status, code) => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('invites')
    h.respond(r => { h.reply(r, null, status as number, code as string); return true })
    await expect(h.runtime.invites.accept(inviteToken)).rejects.toMatchObject({ code })
    expect(h.requests).toHaveLength(1)
    expect(h.requests[0]).toMatchObject({ method: 'POST', url: 'http://127.0.0.1:7631/api/v1/auth/invites/accept', data: { code: 'fresh-code-1', inviteToken } })
    expect(h.requests[0]!.header).not.toHaveProperty('Authorization')
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
  })
  it('accept saves actual returned token then refreshes joined MEMBER context, with fresh code per explicit retry', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('invites')
    let attempt = 0
    h.serverUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER', displayName: null })
    h.respond(r => {
      if (!r.url.endsWith('/auth/invites/accept')) return false
      attempt++
      h.reply(r, attempt === 1 ? null : { state: 'AUTHENTICATED', token: 'server-issued-session', expiresAt: '2099-01-01T00:00:00Z' }, attempt === 1 ? 409 : 200, attempt === 1 ? 'CONFLICT' : 'OK')
      return true
    })
    await expect(h.runtime.invites.accept(inviteToken)).rejects.toMatchObject({ code: 'CONFLICT' })
    await h.runtime.invites.accept(inviteToken)
    expect(h.platform.login).toHaveBeenCalledTimes(2)
    expect(h.requests[1]!.data).toEqual({ code: 'fresh-code-2', inviteToken })
    expect(h.runtime.session.getToken()).toBe('server-issued-session')
    expect(h.requests.slice(2).every(r => r.header.Authorization === 'Bearer server-issued-session')).toBe(true)
    expect(h.runtime.session.getUser()).toMatchObject({ memberId: 22, role: 'MEMBER', displayName: null })
    expect([...h.storage.values()]).toEqual([{ version: 1, token: 'server-issued-session', expiresAt: '2099-01-01T00:00:00Z', environmentId: 'develop|http://127.0.0.1:7631' }])
  })
  it('rejects malformed token before consuming wx code', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('invites')
    for (const token of ['', 'a'.repeat(42), 'a'.repeat(44), 'a'.repeat(42) + '+', '%41'.repeat(43)]) {
      await expect(h.runtime.invites.accept(token)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    }
    expect(h.platform.login).not.toHaveBeenCalled()
  })
  it('self-leave failure preserves session, success clears it without auth/logout', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('members')
    h.setUser({ ...owner, role: 'ADMIN' })
    let succeeds = false
    h.respond(r => {
      if (r.method !== 'DELETE') return false
      h.reply(r, succeeds ? { memberId: 11, status: 'LEFT' } : null, succeeds ? 200 : 409, succeeds ? 'OK' : 'CONFLICT'); return true
    })
    await expect(h.runtime.members.remove(11)).rejects.toMatchObject({ code: 'CONFLICT' })
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
    succeeds = true
    await h.runtime.members.remove(11)
    expect(h.runtime.session.getToken()).toBeNull()
    expect(h.requests.every(r => !r.url.endsWith('/auth/logout'))).toBe(true)
  })
  it('late successful leave from a previous identity does not clear the new identity session', async () => {
    const h = await harness()
    h.setUser({ ...owner, role: 'ADMIN' })
    let pending: import('../../miniprogram/services/http').RawRequestOptions | undefined
    h.respond(r => { pending = r; return true })
    const leave = h.runtime.members.remove(11)
    h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'second-person-session', expiresAt: '2099-01-01T00:00:00Z' })
    h.setUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER' })
    h.reply(pending!, { memberId: 11, status: 'LEFT' })
    await leave
    expect(h.runtime.session.getToken()).toBe('second-person-session')
    expect(h.runtime.session.getUser()?.userId).toBe(2)
  })
})
