import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RawRequestOptions } from '../../miniprogram/services/http'
import { harness, inviteToken, owner } from '../invite-member-harness'

afterEach(() => vi.unstubAllGlobals())

describe('review fix: bind acceptance to authentication attempt and page lifetime', () => {
  it.each(['new-login', 'clear', 'anonymous-clear', 'unload'])('late acceptance cannot replace session after %s', async change => {
    const h = await harness()
    if (change === 'anonymous-clear') h.runtime.session.clear()
    const page = await h.page('invite-accept')
    page.onLoad({ inviteToken })
    let pending: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.endsWith('/auth/invites/accept')) { pending = r; return true }
      if (r.url.endsWith('/auth/wechat/login')) {
        h.reply(r, { state: 'AUTHENTICATED', token: 'new-login-session', expiresAt: '2099-01-01T00:00:00Z' }); return true
      }
      return false
    })
    const accept = page.onAccept()
    await vi.waitFor(() => expect(pending).toBeDefined())
    if (change === 'unload') page.onUnload()
    else {
      h.runtime.session.clear()
      if (change === 'new-login') {
        h.serverUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER' })
        await h.runtime.flow.start()
      }
    }
    h.reply(pending!, { state: 'AUTHENTICATED', token: 'late-accept-session', expiresAt: '2099-01-01T00:00:00Z' })
    await accept
    expect(h.runtime.session.getToken()).toBe(change === 'new-login' ? 'new-login-session' : change === 'unload' ? 'existing-test-session' : null)
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    if (change !== 'unload') expect(page.data.errorMessage).toContain('普通登录')
    expect(JSON.stringify([...h.storage.values()])).not.toContain('late-accept-session')
  })

  it('clearing session while wx code is pending prevents the old submission from reaching HTTP', async () => {
    const h = await harness()
    h.runtime.session.clear()
    let completeCode: ((value: { code: string }) => void) | undefined
    h.platform.login.mockImplementation(options => { completeCode = options.success })
    const page = await h.page('invite-accept')
    page.onLoad({ inviteToken })
    const accept = page.onAccept()
    h.runtime.session.clear()
    completeCode!({ code: 'late-wechat-code' })
    await accept
    expect(h.requests.filter(r => r.url.endsWith('/accept'))).toHaveLength(0)
    expect(h.runtime.session.getToken()).toBeNull()
  })

  it('a newer acceptance attempt supersedes the older result even before either session is saved', async () => {
    const h = await harness()
    h.runtime.session.clear()
    const pending: RawRequestOptions[] = []
    h.respond(r => { if (r.url.endsWith('/accept')) { pending.push(r); return true }; return false })
    const first = h.runtime.invites.accept(inviteToken)
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    const second = h.runtime.invites.accept(inviteToken)
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    h.reply(pending[0]!, { state: 'AUTHENTICATED', token: 'older-accept-session', expiresAt: '2099-01-01T00:00:00Z' })
    await expect(first).rejects.toMatchObject({ code: 'AUTHENTICATION_SUPERSEDED' })
    expect(h.runtime.session.getToken()).toBeNull()
    h.reply(pending[1]!, { state: 'AUTHENTICATED', token: 'newer-accept-session', expiresAt: '2099-01-01T00:00:00Z' })
    await second
    expect(h.runtime.session.getToken()).toBe('newer-accept-session')
  })
})

describe('review fix: old invitation-page generation has no new-page side effects', () => {
  it('a revoke response already in flight cannot erase the next generation invitation', async () => {
    const h = await harness()
    let pending: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.includes('/invites?')) {
        h.reply(r, { items: [{ id: 3, createdBy: 1, createdByName: '甲', createdAt: '2026-09-05T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', status: 'ACTIVE', usedBy: null, usedAt: null }], page: 1, pageSize: 20, total: 1 }); return true
      }
      if (r.method === 'DELETE') { pending = r; return true }
      if (r.method === 'POST') { h.reply(r, { id: 4, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' }); return true }
      return false
    })
    const page = await h.page('invite-create')
    await page.onShow()
    const revoke = page.onRevoke({ currentTarget: { dataset: { id: 3 } } })
    await vi.waitFor(() => expect(pending).toBeDefined())
    page.onHide()
    await page.onShow()
    await page.onCreate()
    expect(page.data.hasInvite).toBe(true)
    h.reply(pending!, { id: 3, status: 'REVOKED' })
    await revoke
    expect(page.data.hasInvite).toBe(true)
    expect(page.data.errorMessage).toBe('')
    page.onCopy()
    expect(h.platform.setClipboardData).toHaveBeenCalledWith({ data: inviteToken })
  })

  it('old cleanup must not reset the busy guard of a new pending creation', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    let oldHistory: RawRequestOptions | undefined
    let newCreate: RawRequestOptions | undefined
    let holdHistory = true
    h.respond(r => {
      if (holdHistory && r.url.includes('/invites?')) { oldHistory = r; return true }
      if (r.method === 'POST') { newCreate = r; return true }
      return false
    })
    const oldFilter = page.onFilterChange({ detail: { value: '1' } })
    page.onHide()
    holdHistory = false
    await page.onShow()
    const create = page.onCreate()
    expect(page.data.busy).toBe(true)
    h.reply(oldHistory!, null, 500, 'INTERNAL_ERROR')
    await oldFilter
    expect(page.data.busy).toBe(true)
    expect(page.data.errorMessage).toBe('')
    await page.onCreate()
    expect(h.requests.filter(r => r.method === 'POST')).toHaveLength(1)
    h.reply(newCreate!, { id: 4, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' })
    await create
    expect(page.data.busy).toBe(false)
    expect(page.data.hasInvite).toBe(true)
  })

  it('old onShow context rejection cannot erase a newly created invitation or overwrite its error view', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    const oldContext: RawRequestOptions[] = []
    let holdContext = true
    h.respond(r => {
      if (holdContext && (r.url.endsWith('/users/me') || r.url.endsWith('/ledger'))) { oldContext.push(r); return true }
      if (r.method === 'POST') { h.reply(r, { id: 3, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' }); return true }
      return false
    })
    const oldShow = page.onShow()
    expect(oldContext).toHaveLength(2)
    page.onHide()
    holdContext = false
    await page.onShow()
    await page.onCreate()
    expect(page.data.hasInvite).toBe(true)
    h.reply(oldContext[0]!, owner)
    h.reply(oldContext[1]!, { id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10 })
    await oldShow
    expect(page.data.hasInvite).toBe(true)
    expect(page.data.errorMessage).toBe('')
    page.onCopy()
    expect(h.platform.setClipboardData).toHaveBeenCalledWith({ data: inviteToken })
  })

  it.each(['create', 'history'])('old %s completion cannot mutate a newly shown page generation', async operation => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    let oldRequest: RawRequestOptions | undefined
    let holdOld = true
    h.respond(r => {
      if (holdOld && (operation === 'create' ? r.method === 'POST' : r.url.includes('/invites?'))) {
        oldRequest = r; return true
      }
      if (r.method === 'POST') { h.reply(r, { id: 4, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' }); return true }
      return false
    })
    const oldOperation = operation === 'create' ? page.onCreate() : page.onFilterChange({ detail: { value: '1' } })
    expect(oldRequest).toBeDefined()
    page.onHide()
    holdOld = false
    await page.onShow()
    expect(page.data.busy).toBe(false)
    await page.onCreate()
    expect(page.data.hasInvite).toBe(true)
    if (operation === 'create') h.reply(oldRequest!, { id: 3, token: 'x'.repeat(43), status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' })
    else h.reply(oldRequest!, null, 500, 'INTERNAL_ERROR')
    await oldOperation
    expect(page.data.hasInvite).toBe(true)
    expect(page.data.errorMessage).toBe('')
    page.onCopy()
    expect(h.platform.setClipboardData).toHaveBeenCalledWith({ data: inviteToken })
  })
})

describe('review fix: confirmation belongs to immutable initiating identity', () => {
  it.each(['member-edit', 'member-list', 'invite-create'])('%s must not send an old confirmation using a new identity after onShow', async name => {
    const h = await harness()
    if (name === 'member-list') h.setUser({ ...owner, role: 'ADMIN' })
    h.respond(r => {
      if (r.url.includes('/invites?')) {
        h.reply(r, { items: [{ id: 3, createdBy: 1, createdByName: '甲', createdAt: '2026-09-05T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', status: 'ACTIVE', usedBy: null, usedAt: null }], page: 1, pageSize: 20, total: 1 }); return true
      }
      if (r.method === 'DELETE') { h.reply(r, name === 'invite-create' ? { id: 3, status: 'REVOKED' } : { memberId: 11, status: 'LEFT' }); return true }
      return false
    })
    const page = await h.page(name)
    if (name === 'member-edit') page.onLoad({ memberId: '22' })
    await page.onShow()
    let modal: { success(value: { confirm: boolean; cancel: boolean }): void } | undefined
    h.platform.showModal.mockImplementation(options => { modal = options })
    const action = name === 'member-edit' ? page.onRemove() : name === 'member-list' ? page.onLeave() : page.onRevoke({ currentTarget: { dataset: { id: 3 } } })
    expect(modal).toBeDefined()
    h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'new-admin-session', expiresAt: '2099-01-01T00:00:00Z' })
    h.setUser({ ...owner, userId: 3, memberId: 33, role: 'ADMIN' })
    await page.onShow()
    modal!.success({ confirm: true, cancel: false })
    await action
    expect(h.requests.filter(r => r.method === 'DELETE')).toHaveLength(0)
    expect(h.runtime.session.getToken()).toBe('new-admin-session')
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    expect(h.platform.redirectTo).not.toHaveBeenCalled()
  })
})
