import { afterEach, describe, expect, it, vi } from 'vitest'
import { harness, inviteToken, other, owner } from '../invite-member-harness'
import type { RawRequestOptions } from '../../miniprogram/services/http'
import { readFileSync } from 'node:fs'

afterEach(() => vi.unstubAllGlobals())

describe('actual invite pages at Page/wx boundary', () => {
  it('invite history empty state is excluded while a read error is visible', () => {
    const wxml = readFileSync(new URL('../../miniprogram/pages/invite-create/index.wxml', import.meta.url), 'utf8')
    expect(wxml).toMatch(/!items\.length[^>]*!loading[^>]*!errorMessage/)
  })

  it('failed invite history keeps the actual page in error state with no empty-success rows', async () => {
    const h = await harness()
    h.respond(r => {
      if (r.url.includes('/invites?')) {
        h.reply(r, null, 500, 'INVITE_HISTORY_FAILED')
        return true
      }
      return false
    })
    const page = await h.page('invite-create')

    await page.onShow()

    expect(page.data.items).toEqual([])
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.loading).toBe(false)
  })

  it('direct invite entry never auto-logins; duplicate clicks consume one code; success enters home', async () => {
    const h = await harness()
    const page = await h.page('invite-accept')
    page.onLoad({ inviteToken: ' ' + inviteToken + ' ' })
    expect(h.platform.login).not.toHaveBeenCalled()
    let pending: RawRequestOptions | undefined
    h.respond(r => { if (r.url.endsWith('/accept')) { pending = r; return true }; return false })
    const first = page.onAccept()
    await page.onAccept()
    expect(h.platform.login).toHaveBeenCalledTimes(1)
    await vi.waitFor(() => expect(pending).toBeDefined())
    h.reply(pending!, { state: 'AUTHENTICATED', token: 'accepted-session', expiresAt: '2099-01-01T00:00:00Z' })
    await first
    expect(pending!.data).toEqual({ code: 'fresh-code-1', inviteToken })
    expect(h.runtime.session.getToken()).toBe('accepted-session')
    expect(h.platform.reLaunch).toHaveBeenCalledWith({ url: '/pages/home/index' })
    expect(page.data.busy).toBe(false)
    expect(page.data.inviteToken).toBe('')
  })
  it('accept failure keeps session and code is reacquired on next manual click; ALREADY_MEMBER offers navigation', async () => {
    const h = await harness()
    const page = await h.page('invite-accept')
    page.onLoad({})
    page.onTokenInput({ detail: { value: inviteToken } })
    h.respond(r => { h.reply(r, null, 409, 'ALREADY_MEMBER'); return true })
    await page.onAccept()
    await page.onAccept()
    expect(h.platform.login).toHaveBeenCalledTimes(2)
    expect(page.data.alreadyMember).toBe(true)
    expect(page.data.busy).toBe(false)
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
    page.openEntry()
    expect(h.platform.reLaunch).toHaveBeenCalledWith({ url: '/pages/home/index' })
    page.onUnload()
    expect(page.data.inviteToken).toBe('')
  })
  it('login forwards share token without ordinary login and offers manual accept navigation', async () => {
    const h = await harness()
    const page = await h.page('login')
    await page.onLoad({ inviteToken })
    expect(h.platform.login).not.toHaveBeenCalled()
    expect(h.platform.redirectTo).toHaveBeenCalledWith({ url: '/pages/invite-accept/index?inviteToken=' + inviteToken })
    page.openInvite()
    expect(h.platform.navigateTo).toHaveBeenCalledWith({ url: '/pages/invite-accept/index' })
  })
  it('manager create code is page-only, manually copied/shared, retained only for share hide and cleared on ordinary hide/unload', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    h.respond(r => {
      if (r.url.endsWith('/invites') && r.method === 'POST') { h.reply(r, { id: 3, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' }); return true }
      return false
    })
    await page.onCreate()
    expect(h.requests.find(r => r.method === 'POST')?.data).toEqual({ expiresInHours: 24 })
    expect(h.platform.setClipboardData).not.toHaveBeenCalled()
    expect(JSON.stringify(page.data)).not.toContain(inviteToken)
    expect(JSON.stringify([...h.storage.values()])).not.toContain(inviteToken)
    page.onCopy()
    expect(h.platform.setClipboardData).toHaveBeenCalledWith(expect.objectContaining({ data: inviteToken }))
    expect(page.onShareAppMessage()).toEqual({ title: '邀请你加入共享账本', path: '/pages/invite-accept/index?inviteToken=' + inviteToken })
    page.onHide()
    expect(page.data.hasInvite).toBe(true)
    await page.onShow()
    page.onHide()
    expect(page.data.hasInvite).toBe(false)
    expect(page.onShareAppMessage().path).toBe('/pages/invite-accept/index')
    page.onUnload()
    expect(page.data.hasInvite).toBe(false)
  })
  it.each(['unload', 'identity', 'demotion'])('late create response cannot refill raw code after %s', async loss => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    let pending: RawRequestOptions | undefined
    h.respond(r => { if (r.method === 'POST') { pending = r; return true }; return false })
    const create = page.onCreate()
    await vi.waitFor(() => expect(pending).toBeDefined())
    if (loss === 'unload') page.onUnload()
    else h.setUser({ ...owner, userId: loss === 'identity' ? 2 : 1, role: loss === 'demotion' ? 'MEMBER' : 'OWNER' })
    h.reply(pending!, { id: 3, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' })
    await create
    expect(page.data.hasInvite).toBe(false)
    if (loss !== 'unload') expect(page.data.canManage).toBe(false)
    expect(page.onShareAppMessage().path).toBe('/pages/invite-accept/index')
    page.onCopy()
    expect(h.platform.setClipboardData).not.toHaveBeenCalled()
  })
  it('latest MEMBER identity hides managers actions and prevents create/revoke/copy', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    h.serverUser({ ...owner, role: 'MEMBER' })
    await page.onShow()
    expect(page.data.canManage).toBe(false)
    await page.onCreate()
    await page.onRevoke({ currentTarget: { dataset: { id: 3 } } })
    expect(h.requests.every(r => r.method === 'GET')).toBe(true)
    expect(h.platform.showModal).not.toHaveBeenCalled()
  })
  it('late history response after identity loss clears the visible management controls', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    await page.onShow()
    let pending: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.includes('/invites?')) { pending = r; return true }
      return false
    })
    const filter = page.onFilterChange({ detail: { value: '1' } })
    h.runtime.session.clear()
    h.reply(pending!, { items: [], page: 1, pageSize: 20, total: 0 })
    await filter
    expect(page.data.canManage).toBe(false)
    expect(page.data.hasInvite).toBe(false)
  })
  it('invite list filter resets pagination; next page replaces results and revoke confirms once', async () => {
    const h = await harness()
    const page = await h.page('invite-create')
    h.respond(r => {
      if (r.url.includes('/invites?')) {
        const p = r.url.includes('page=2') ? 2 : 1
        h.reply(r, { items: [{ id: p, createdBy: 1, createdByName: '甲', createdAt: '2026-09-05T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', status: 'ACTIVE', usedBy: null, usedAt: null }], page: p, pageSize: 20, total: 21 })
        return true
      }
      if (r.method === 'DELETE') { h.reply(r, { id: 1, status: 'REVOKED' }); return true }
      return false
    })
    await page.onShow()
    await page.nextPage()
    expect(page.data.page).toBe(2)
    expect(page.data.items.map((x: { id: number }) => x.id)).toEqual([2])
    await page.onFilterChange({ detail: { value: '1' } })
    expect(page.data.page).toBe(1)
    expect(h.requests.some(r => r.url.endsWith('page=1&pageSize=20&status=ACTIVE'))).toBe(true)
    await page.onRevoke({ currentTarget: { dataset: { id: 1 } } })
    expect(h.platform.showModal).toHaveBeenCalledTimes(1)
    expect(h.requests.filter(r => r.method === 'DELETE')).toHaveLength(1)
  })
})

describe('actual member pages', () => {
  it('member list ignores an old read rejection after a newer show completes', async () => {
    const h = await harness()
    const page = await h.page('member-list')
    const reads: RawRequestOptions[] = []
    h.respond(r => {
      if (r.url.endsWith('/members') && r.method === 'GET') {
        reads.push(r)
        return true
      }
      return false
    })

    const oldLoad = page.onShow()
    await vi.waitFor(() => expect(reads).toHaveLength(1))
    page.onUnload()
    const newLoad = page.onShow()
    await vi.waitFor(() => expect(reads).toHaveLength(2))
    h.reply(reads[1]!, { items: [{ ...other, nickname: '新成员' }], activeCount: 1, maxMembers: 10, ownerUserId: 1 })
    await newLoad
    h.reply(reads[0]!, null, 500, 'OLD_MEMBER_READ')
    await oldLoad

    expect(page.data.items).toEqual([expect.objectContaining({ name: '新成员' })])
    expect(page.data.errorMessage).toBe('')
    expect(page.data.loading).toBe(false)
  })

  it('member detail ignores an old read rejection after a newer show completes', async () => {
    const h = await harness()
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    const reads: RawRequestOptions[] = []
    h.respond(r => {
      if (r.url.endsWith('/members') && r.method === 'GET') {
        reads.push(r)
        return true
      }
      return false
    })

    const oldLoad = page.onShow()
    await vi.waitFor(() => expect(reads).toHaveLength(1))
    page.onUnload()
    page.onShow()
    await vi.waitFor(() => expect(reads).toHaveLength(2))
    h.reply(reads[1]!, { items: [{ ...other, nickname: '新成员' }], activeCount: 1, maxMembers: 10, ownerUserId: 1 })
    await Promise.resolve()
    h.reply(reads[0]!, null, 500, 'OLD_MEMBER_READ')
    await oldLoad

    expect(page.data.target).toEqual(expect.objectContaining({ nickname: '新成员' }))
    expect(page.data.errorMessage).toBe('')
    expect(page.data.loading).toBe(false)
  })

  it('member list displays alias-null nickname/capacity and navigates by member ID', async () => {
    const h = await harness()
    const page = await h.page('member-list')
    await page.onShow()
    expect(page.data.items[0]).toMatchObject({ name: '成员昵称', roleLabel: '普通成员', memberId: 22 })
    expect(page.data.activeCount).toBe(2)
    expect(page.data.maxMembers).toBe(10)
    page.openMember({ currentTarget: { dataset: { id: 22 } } })
    expect(h.platform.navigateTo).toHaveBeenCalledWith({ url: '/pages/member-edit/index?memberId=22' })
  })
  it.each(['MEMBER', 'ADMIN', 'OWNER'] as const)('%s action permissions follow current identity and target', async role => {
    const h = await harness()
    h.setUser({ ...owner, role })
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    await page.onShow()
    expect(page.data.canChangeRole).toBe(role === 'OWNER')
    expect(page.data.canTransfer).toBe(role === 'OWNER')
    expect(page.data.canRemove).toBe(role !== 'MEMBER')
    h.serverUser({ ...owner, role: 'MEMBER' })
    await page.onShow()
    await page.onTransfer()
    await page.onRemove()
    await page.onChangeRole()
    expect(h.requests.every(r => r.method === 'GET')).toBe(true)
    expect(h.platform.showModal).not.toHaveBeenCalled()
  })
  it('transfer confirms once with MEMBER warning and updates former owner permissions', async () => {
    const h = await harness()
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    await page.onShow()
    h.respond(r => {
      if (!r.url.endsWith('/transfer-ownership')) return false
      h.serverUser({ ...owner, role: 'MEMBER' })
      h.reply(r, { ownerUserId: 2, previousOwnerUserId: 1 }); return true
    })
    await page.onTransfer()
    expect(h.platform.showModal).toHaveBeenCalledTimes(1)
    expect(h.platform.showModal.mock.calls[0]![0]).toMatchObject({ content: expect.stringContaining('你将成为普通成员') })
    expect(page.data.canTransfer).toBe(false)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })
  it('cancelled removal does not send DELETE and busy prevents duplicate confirmation', async () => {
    const h = await harness()
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    await page.onShow()
    let modal: { success(v: { confirm: boolean; cancel: boolean }): void } | undefined
    h.platform.showModal.mockImplementation(options => { modal = options })
    const pending = page.onRemove()
    await page.onRemove()
    expect(h.platform.showModal).toHaveBeenCalledTimes(1)
    modal!.success({ confirm: false, cancel: true })
    await pending
    expect(h.requests.every(r => r.method === 'GET')).toBe(true)
    expect(page.data.busy).toBe(false)
  })
  it('actual role change uses the selected target and a single confirmation; remove navigates to members', async () => {
    const h = await harness()
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    await page.onShow()
    h.respond(r => {
      if (r.url.endsWith('/role')) {
        const role = (r.data as { role: string }).role
        h.setMembers([{ ...other, role }])
        h.reply(r, { ...other, role }); return true
      }
      if (r.method === 'DELETE') { h.reply(r, { memberId: 22, status: 'REMOVED' }); return true }
      return false
    })
    await page.onChangeRole()
    expect(page.data.roleName).toBe('管理员')
    expect(page.data.canRemove).toBe(false)
    expect(h.platform.showModal).toHaveBeenCalledTimes(1)
    await page.onChangeRole()
    expect(page.data.roleName).toBe('普通成员')
    await page.onRemove()
    expect(h.platform.showModal).toHaveBeenCalledTimes(3)
    expect(h.platform.redirectTo).toHaveBeenCalledWith({ url: '/pages/member-list/index' })
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
  })
  it('identity switch while removal confirmation is open cancels the old identity action', async () => {
    const h = await harness()
    const page = await h.page('member-edit')
    page.onLoad({ memberId: '22' })
    await page.onShow()
    let modal: { success(v: { confirm: boolean; cancel: boolean }): void } | undefined
    h.platform.showModal.mockImplementation(options => { modal = options })
    const pending = page.onRemove()
    h.setUser({ ...owner, userId: 3, memberId: 33 })
    modal!.success({ confirm: true, cancel: false })
    await pending
    expect(h.requests.filter(r => r.method === 'DELETE')).toHaveLength(0)
    expect(page.data.canRemove).toBe(false)
  })
  it('ADMIN cannot remove another ADMIN; own exit sends member DELETE then clears session', async () => {
    const h = await harness()
    h.setUser({ ...owner, role: 'ADMIN' })
    h.setMembers([{ ...other, role: 'ADMIN' }])
    const edit = await h.page('member-edit')
    edit.onLoad({ memberId: '22' })
    await edit.onShow()
    expect(edit.data.canRemove).toBe(false)
    const list = await h.page('member-list')
    await list.onShow()
    h.respond(r => { if (r.method !== 'DELETE') return false; h.reply(r, { memberId: 11, status: 'LEFT' }); return true })
    await list.onLeave()
    expect(h.platform.showModal).toHaveBeenCalledTimes(1)
    expect(h.requests.find(r => r.method === 'DELETE')?.url).toBe('http://127.0.0.1:7631/api/v1/members/11')
    expect(h.runtime.session.getToken()).toBeNull()
    expect(h.platform.reLaunch).toHaveBeenCalledWith({ url: '/pages/login/index' })
  })
  it('late leave result cannot navigate a newly established identity to login', async () => {
    const h = await harness()
    h.setUser({ ...owner, role: 'ADMIN' })
    const page = await h.page('member-list')
    await page.onShow()
    let pending: RawRequestOptions | undefined
    h.respond(r => { pending = r; return true })
    const leave = page.onLeave()
    await vi.waitFor(() => expect(pending).toBeDefined())
    h.runtime.session.saveAuthenticated({ state: 'AUTHENTICATED', token: 'new-identity-session', expiresAt: '2099-01-01T00:00:00Z' })
    h.setUser({ ...owner, userId: 2, memberId: 22, role: 'MEMBER' })
    h.reply(pending!, { memberId: 11, status: 'LEFT' })
    await leave
    expect(h.runtime.session.getToken()).toBe('new-identity-session')
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
  })
})
