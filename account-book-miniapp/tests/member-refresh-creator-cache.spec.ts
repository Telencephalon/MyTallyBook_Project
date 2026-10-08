import { afterEach, describe, expect, it, vi } from 'vitest'
import { harness, other, owner } from './invite-member-harness'
import type { Entry } from '../miniprogram/types/entry'

const ownerMember = { ...other, memberId: 11, userId: 1, nickname: 'Gitta', role: 'OWNER' }
const remainingMember = { ...other, nickname: '熊小小' }
const departedMember = { ...other, memberId: 33, userId: 3, nickname: '陈泓宇' }
const ownEntry: Entry = {
  id: 40, entryType: 'INCOME', amount: '100.00', categoryId: 7, categoryName: '生活',
  categoryStatus: 'ACTIVE', accountId: 8, accountName: '现金', accountStatus: 'ACTIVE',
  entryDate: '2026-09-01', personName: '测试', note: null, createdBy: 1, creatorName: 'Gitta',
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
  version: 0, canEdit: true, canDelete: true,
}

afterEach(() => { vi.resetModules(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('creator options after refreshing current members', () => {
  it.each(['home', 'entry-list', 'statistics'])('%s drops an externally departed creator immediately on return from the member page', async name => {
    // Keep the return inside the normal 15-second tab freshness window.
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-01T00:00:00Z'))
    const h = await harness()
    h.setUser({ ...owner, nickname: 'Gitta' })
    h.setMembers([ownerMember, remainingMember, departedMember])
    let departed = false
    h.respond(request => {
      const url = new URL(request.url)
      const createdBy = Number(url.searchParams.get('createdBy'))
      if (url.pathname === '/api/v1/entries/creators') {
        h.reply(request, { items: (departed ? [ownerMember, remainingMember]
          : [ownerMember, remainingMember, departedMember])
          .map(item => ({ userId: item.userId, displayName: item.nickname })) })
        return true
      }
      if (url.pathname === '/api/v1/entries') {
        const entry = createdBy === 3 ? { ...ownEntry, id: 41, amount: '70.00', createdBy: 3, creatorName: '陈泓宇' } : ownEntry
        h.reply(request, { items: [entry], page: Number(url.searchParams.get('page')) || 1,
          pageSize: Number(url.searchParams.get('pageSize')) || 20, total: 1 })
        return true
      }
      if (url.pathname === '/api/v1/categories' || url.pathname === '/api/v1/accounts') {
        h.reply(request, { items: [] })
        return true
      }
      if (url.pathname.startsWith('/api/v1/statistics/')) {
        const month = url.searchParams.get('month') || '2026-09'
        const period = { month, rangeType: 'MONTH', startDate: `${month}-01`, endDate: `${month}-30` }
        const income = createdBy === 3 ? '70.00' : '100.00'
        if (url.pathname.endsWith('/monthly-summary')) {
          h.reply(request, { ...period, income, expense: '0.00', net: income, entryCount: 1 })
        } else if (url.pathname.endsWith('/daily-trend')) {
          h.reply(request, { ...period, items: [{ date: `${month}-01`, income, expense: '0.00', net: income, entryCount: 1 }],
            page: 1, pageSize: 31, totalDays: 30, totalPages: 1, hasNext: false })
        } else if (url.pathname.endsWith('/accounts')) {
          h.reply(request, { ...period, items: [{ id: 8, name: '现金', income, expense: '0.00', net: income, entryCount: 1 }] })
        } else {
          h.reply(request, { ...period, entryType: url.searchParams.get('entryType'), total: '0.00', items: [] })
        }
        return true
      }
      return false
    })

    const page = await h.page(name)
    await page.onShow()
    await page.onCreator({ detail: { value: '3' } })
    expect(page.data.createdBy).toBe(3)
    page.onHide()

    // This server change happened on another phone, with no local DELETE request.
    departed = true
    h.setMembers([ownerMember, remainingMember])
    const members = await h.page('member-list')
    await members.onShow()
    expect(members.data.items.map((item: { userId: number }) => item.userId)).toEqual([1, 2])
    await page.onShow()

    expect(page.data.creators).toEqual([
      { userId: 0, displayName: '全部成员' },
      { userId: 1, displayName: 'Gitta' },
      { userId: 2, displayName: '熊小小' },
    ])
    expect(page.data.createdBy).toBe(1)
    if (name === 'statistics') expect(page.data.summary.income).toBe('100.00')
    else expect((name === 'home' ? page.data.recentEntries : page.data.items)[0].createdBy).toBe(1)
  })
})
