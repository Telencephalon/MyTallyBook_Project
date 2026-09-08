import { afterEach, describe, expect, it, vi } from 'vitest'
import { harness, inviteToken, other } from '../invite-member-harness'

afterEach(() => vi.unstubAllGlobals())
describe('invitation and member wire contracts through real runtime', () => {
  it('creates with hours, lists with pagination/status, revokes without body', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('invites')
    h.respond(r => {
      if (r.url.endsWith('/invites') && r.method === 'POST') { h.reply(r, { id: 3, token: inviteToken, expiresAt: '2099-01-01T00:00:00Z', status: 'ACTIVE' }); return true }
      if (r.url.endsWith('/invites/3')) { h.reply(r, { id: 3, status: 'REVOKED' }); return true }
      if (r.url.includes('/invites?')) { h.reply(r, { items: [], page: 2, pageSize: 50, total: 0 }); return true }
      return false
    })
    await h.runtime.invites.create(168)
    await h.runtime.invites.list({ page: 2, pageSize: 50, status: 'ACTIVE' })
    await h.runtime.invites.revoke(3)
    expect(h.requests.filter(r => r.url.includes('/invites'))).toMatchObject([
      { method: 'POST', data: { expiresInHours: 168 }, header: { Authorization: 'Bearer existing-test-session' } },
      { method: 'GET', url: 'http://127.0.0.1:7631/api/v1/invites?page=2&pageSize=50&status=ACTIVE' },
      { method: 'DELETE', url: 'http://127.0.0.1:7631/api/v1/invites/3' },
    ])
    expect(h.requests.find(r => r.method === 'DELETE')?.data).toBeUndefined()
  })

  it('uses PUT role and exact memberId routes without privileged extras', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('members')
    h.respond(r => {
      if (r.url.endsWith('/role')) { h.reply(r, { ...other, role: 'ADMIN' }); return true }
      if (r.url.endsWith('/transfer-ownership')) { h.reply(r, { ownerUserId: 2, previousOwnerUserId: 1 }); return true }
      if (r.method === 'DELETE') { h.reply(r, { memberId: 22, status: 'REMOVED' }); return true }
      return false
    })
    await h.runtime.members.changeRole(22, 'ADMIN')
    await h.runtime.members.transfer(22)
    await h.runtime.members.remove(22)
    expect(h.requests.filter(r => r.method !== 'GET')).toMatchObject([
      { method: 'PUT', url: 'http://127.0.0.1:7631/api/v1/members/22/role', data: { role: 'ADMIN' } },
      { method: 'POST', url: 'http://127.0.0.1:7631/api/v1/members/22/transfer-ownership' },
      { method: 'DELETE', url: 'http://127.0.0.1:7631/api/v1/members/22' },
    ])
  })

  it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('rejects unsafe IDs and invalid hours %s before network', async value => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('members')
    await expect(h.runtime.members.remove(value)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    await expect(h.runtime.invites.revoke(value)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    await expect(h.runtime.invites.create(value)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(h.requests).toHaveLength(0)
  })
  it('rejects invalid pagination, statuses and hour maximum before network', async () => {
    const h = await harness()
    expect(h.runtime).toHaveProperty('invites')
    for (const query of [{ page: 0 }, { pageSize: 51 }, { page: 1.5 }, { status: 'ALL' }, { page: Number.MAX_SAFE_INTEGER }, { page: 2147483648, pageSize: 1 }]) {
      await expect(h.runtime.invites.list(query as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    }
    await expect(h.runtime.invites.create(169)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(h.requests).toHaveLength(0)
  })
  it('allows one-hour invitations and refuses OWNER role on the role endpoint', async () => {
    const h = await harness()
    h.respond(r => {
      if (r.method !== 'POST') return false
      h.reply(r, { id: 1, token: inviteToken, status: 'ACTIVE', expiresAt: '2099-01-01T00:00:00Z' }); return true
    })
    await h.runtime.invites.create(1)
    expect(h.requests[0]?.data).toEqual({ expiresInHours: 1 })
    await expect(h.runtime.members.changeRole(22, 'OWNER' as never)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' })
    expect(h.requests.filter(r => r.method === 'PUT')).toHaveLength(0)
  })
})
