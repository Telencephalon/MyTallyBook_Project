import { afterEach, describe, expect, it, vi } from 'vitest'
import type { RawRequestOptions } from '../../miniprogram/services/http'
import { harness, inviteToken, ledger, owner } from '../invite-member-harness'

afterEach(() => vi.unstubAllGlobals())

// Only HTTP/wx are substituted. SessionFlow, InviteFlow, MemberFlow, HttpClient,
// SessionStore and the loaded Page methods all run as shipped.
async function pausedMutation(kind: 'logout' | 'nickname') {
  const h = await harness()
  let pending: RawRequestOptions | undefined
  h.respond(r => {
    if ((kind === 'logout' && r.url.endsWith('/auth/logout'))
      || (kind === 'nickname' && r.url.endsWith('/users/me') && r.method === 'PUT')) {
      pending = r
      return true
    }
    if (r.url.endsWith('/invites/accept')) {
      h.reply(r, { state: 'AUTHENTICATED', token: 'new-session', expiresAt: '2099-01-01T00:00:00Z' })
      return true
    }
    if (r.url.endsWith('/members/22/transfer-ownership')) {
      h.serverUser({ ...owner, role: 'MEMBER' })
      h.reply(r, { previousOwnerUserId: 1, ownerUserId: 2 })
      return true
    }
    return false
  })
  return {
    ...h,
    request: () => {
      expect(pending, 'real mutation must reach the HTTP boundary').toBeDefined()
      return pending!
    },
    async acceptNewIdentity() {
      h.serverUser({ ...owner, userId: 2, memberId: 22, nickname: '新身份', role: 'MEMBER' })
      await h.runtime.invites.accept(inviteToken)
      expect(h.runtime.session.getToken()).toBe('new-session')
    },
  }
}

describe('final review: actual home logout across invite acceptance', () => {
  it.each([200, 401])('old logout %s cannot clear or redirect the accepted identity', async status => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const operation = page.submitLogout()
    expect(h.request().header.Authorization).toBe('Bearer existing-test-session')
    await h.acceptNewIdentity()
    const revision = h.runtime.session.getRevision()
    h.reply(h.request(), { state: 'LOGGED_OUT' }, status, status === 200 ? 'OK' : 'AUTHENTICATION_REQUIRED')
    await operation
    expect(h.runtime.session.getToken()).toBe('new-session')
    expect(h.runtime.session.getRevision()).toBe(revision)
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    expect(page.data.errorMessage).toBe('')
  })

  it('successful old logout cannot clear a re-established same-token identity epoch', async () => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const operation = page.submitLogout()
    await h.runtime.flow.establishAuthenticatedSession({ state: 'AUTHENTICATED', token: 'existing-test-session', expiresAt: '2099-01-01T00:00:00Z' })
    h.reply(h.request(), { state: 'LOGGED_OUT' })
    await operation
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
  })

  it.each([200, 401])('current logout %s clears exactly once and navigates exactly once', async status => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const revision = h.runtime.session.getRevision()
    const operation = page.submitLogout()
    h.reply(h.request(), { state: 'LOGGED_OUT' }, status, status === 200 ? 'OK' : 'AUTHENTICATION_REQUIRED')
    await operation
    expect(h.runtime.session.getToken()).toBeNull()
    expect(h.runtime.session.getRevision()).toBe(revision + 1)
    expect(h.platform.reLaunch).toHaveBeenCalledExactlyOnceWith({ url: '/pages/login/index' })
    expect(page.data.loggingOut).toBe(false)
    expect(page.data.errorMessage).toBe('')
  })

  it('current network failure preserves identity and exposes a retryable error', async () => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const operation = page.submitLogout()
    h.request().fail({ errMsg: 'request:fail offline' })
    await operation
    expect(h.runtime.session.getToken()).toBe('existing-test-session')
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.loggingOut).toBe(false)
  })

  it('old logout failure cannot paint an error onto a newly shown identity', async () => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const operation = page.submitLogout()
    await h.acceptNewIdentity()
    page.onShow()
    await vi.waitFor(() => expect(page.data.loading).toBe(false))
    const data = structuredClone(page.data)
    h.request().fail({ errMsg: 'request:fail offline' })
    await operation
    expect(page.data).toEqual(data)
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
  })

  it.each(['onHide', 'onUnload'])('logout success after %s cannot navigate or mutate the departed page', async lifecycle => {
    const h = await pausedMutation('logout')
    const page = await h.page('home')
    const operation = page.submitLogout()
    page[lifecycle]?.()
    const data = structuredClone(page.data)
    h.reply(h.request(), { state: 'LOGGED_OUT' })
    await operation
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    expect(page.data).toEqual(data)
  })
})

describe('final review: actual nickname flow and profile across context changes', () => {
  it.each(['identity', 'transfer'] as const)('rejects an obsolete nickname write after actual %s flow', async change => {
    const h = await pausedMutation('nickname')
    const result = h.runtime.flow.updateNickname('旧身份新昵称').then(() => 'accepted', error => error)
    if (change === 'identity') await h.acceptNewIdentity()
    else await h.runtime.members.transfer(22)
    h.reply(h.request(), { ...owner, nickname: '旧身份新昵称' })
    expect(await result).toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.runtime.session.getUser()?.nickname).toBe(change === 'identity' ? '新身份' : owner.nickname)
    expect(h.runtime.session.getLedger()).toEqual(ledger)
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(1)
  })

  it.each(['identity', 'transfer'] as const)('profile response after %s preserves context and releases its own save with a refresh prompt', async change => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '旧身份新昵称' } })
    const operation = page.saveProfile()
    if (change === 'identity') await h.acceptNewIdentity()
    else await h.runtime.members.transfer(22)
    const data = structuredClone(page.data)
    h.reply(h.request(), { ...owner, nickname: '旧身份新昵称' })
    await operation
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(page.data).toEqual({ ...data, saving: false, errorMessage: expect.stringMatching(/刷新/), requestId: '' })
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(1)

    // Recovery is explicit: refresh/reload, then a new click can submit once.
    await h.runtime.flow.refreshContext()
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '当前身份新昵称' } })
    const retry = page.saveProfile()
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(2)
    h.reply(h.request(), { ...h.runtime.session.getUser(), nickname: '当前身份新昵称' })
    await retry
    expect(page.data.saving).toBe(false)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.platform.navigateBack).toHaveBeenCalledTimes(1)
  })

  it.each(['network', 'server'] as const)('obsolete profile %s failure releases its own save without exposing the old error', async failure => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '旧身份新昵称' } })
    const operation = page.saveProfile()
    await h.runtime.members.transfer(22)
    if (failure === 'network') h.request().fail({ errMsg: 'request:fail old request' })
    else h.reply(h.request(), null, 409, 'OLD_MUTATION_ERROR')
    await operation
    expect(page.data.saving).toBe(false)
    expect(page.data.errorMessage).toMatch(/刷新/)
    expect(page.data.errorMessage).not.toMatch(/安全错误|网络连接失败/)
    expect(page.data.requestId).toBe('')
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(1)
  })

  it('resolved unchanged nickname save shows recovery when context changes before the page continuation', async () => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    let contextRequest: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.endsWith('/users/me') && r.method === 'GET') { contextRequest = r; return true }
      return false
    })
    // The real flow resolves synchronously for an unchanged nickname, but the
    // page's awaited success continuation has not run when refresh starts.
    const operation = page.saveProfile()
    const refresh = h.runtime.flow.refreshContext()
    expect(contextRequest).toBeDefined()
    await operation
    const settledData = structuredClone(page.data)
    h.reply(contextRequest!, { ...owner, role: 'MEMBER' })
    await refresh
    expect(settledData.saving).toBe(false)
    expect(settledData.errorMessage).toMatch(/刷新/)
    expect(settledData.requestId).toBe('')
    expect(settledData.originalNickname).toBe(owner.nickname)
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(h.platform.reLaunch).not.toHaveBeenCalled()
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(0)
  })

  it('profile save releases its own busy state when the superseding refresh is still pending', async () => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '等待中的昵称' } })
    const operation = page.saveProfile()
    const nicknameRequest = h.request()
    let contextRequest: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.endsWith('/users/me') && r.method === 'GET') { contextRequest = r; return true }
      return false
    })
    const refresh = h.runtime.flow.refreshContext()
    expect(contextRequest).toBeDefined()
    h.reply(nicknameRequest, { ...owner, nickname: '等待中的昵称' })
    await operation
    const settledData = structuredClone(page.data)
    // Finish the controlled HTTP fixture before any potentially failing assertion.
    h.reply(contextRequest!, { ...owner, role: 'MEMBER' })
    await refresh
    expect(settledData.saving).toBe(false)
    expect(settledData.errorMessage).toMatch(/刷新/)
    expect(settledData.requestId).toBe('')
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
    expect(h.runtime.session.getUser()?.nickname).toBe(owner.nickname)
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(1)
  })

  it.each(['onHide', 'onUnload'])('late profile success after %s has no page callbacks', async lifecycle => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '新昵称' } })
    const operation = page.saveProfile()
    page[lifecycle]?.()
    const data = structuredClone(page.data)
    h.reply(h.request(), { ...owner, nickname: '新昵称' })
    await operation
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(page.data).toEqual(data)
  })

  it('nickname response cannot overwrite context once a newer refresh has started but not completed', async () => {
    const h = await pausedMutation('nickname')
    const result = h.runtime.flow.updateNickname('旧昵称修改').then(() => 'accepted', error => error)
    const nicknameRequest = h.request()
    let contextRequest: RawRequestOptions | undefined
    h.respond(r => {
      if (r.url.endsWith('/users/me') && r.method === 'GET') { contextRequest = r; return true }
      return false
    })
    const refresh = h.runtime.flow.refreshContext()
    expect(contextRequest).toBeDefined()
    h.reply(nicknameRequest, { ...owner, nickname: '旧昵称修改' })
    const actual = await result
    h.reply(contextRequest!, { ...owner, role: 'MEMBER' })
    await refresh
    expect(actual).toMatchObject({ code: 'SESSION_CONTEXT_CHANGED' })
    expect(h.runtime.session.getUser()?.role).toBe('MEMBER')
  })

  it.each(['success', 'failure'])('old profile %s cannot overwrite a newer show or reset its pending save', async outcome => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '第一次修改' } })
    const oldOperation = page.saveProfile()
    const oldRequest = h.request()
    page.onHide?.()
    page.onShow?.()
    await vi.waitFor(() => expect(page.data.loading).toBe(false))
    page.onNicknameInput({ detail: { value: '第二次修改' } })
    const newOperation = page.saveProfile()
    const newRequest = h.request()
    expect(newRequest).not.toBe(oldRequest)
    if (outcome === 'success') h.reply(oldRequest, { ...owner, nickname: '第一次修改' })
    else oldRequest.fail({ errMsg: 'request:fail offline' })
    await oldOperation
    expect(page.data.saving).toBe(true)
    expect(page.data.errorMessage).toBe('')
    expect(h.runtime.session.getUser()?.nickname).toBe(owner.nickname)
    h.reply(newRequest, { ...owner, nickname: '第二次修改' })
    await newOperation
    expect(page.data.originalNickname).toBe('第二次修改')
    expect(h.platform.navigateBack).toHaveBeenCalledTimes(1)
  })

  it('profile network failure stays on the page and releases the save guard', async () => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '新昵称' } })
    const operation = page.saveProfile()
    h.request().fail({ errMsg: 'request:fail offline' })
    await operation
    expect(page.data.errorMessage).not.toBe('')
    expect(page.data.saving).toBe(false)
    expect(h.platform.navigateBack).not.toHaveBeenCalled()
    expect(h.runtime.session.getUser()?.nickname).toBe(owner.nickname)
  })

  it('normal profile edit writes returned nickname and navigates once', async () => {
    const h = await pausedMutation('nickname')
    const page = await h.page('profile')
    await page.loadProfile()
    page.onNicknameInput({ detail: { value: '  新昵称  ' } })
    const operation = page.saveProfile()
    expect(h.request().data).toEqual({ nickname: '新昵称' })
    h.reply(h.request(), { ...owner, nickname: '新昵称' })
    await operation
    expect(h.runtime.session.getUser()?.nickname).toBe('新昵称')
    expect(page.data.originalNickname).toBe('新昵称')
    expect(page.data.saving).toBe(false)
    expect(h.platform.navigateBack).toHaveBeenCalledTimes(1)
  })
})
