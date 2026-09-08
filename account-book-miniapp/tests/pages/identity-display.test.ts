import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

import { getRuntime } from '../../miniprogram/runtime'
import type { Ledger, UserProfile } from '../../miniprogram/types/api'

interface IdentityData {
  displayName: string
  memberRoleLabel: string
  nickname: string
  loading: boolean
  errorMessage: string
  canManageInvites: boolean
}

interface PageBoundary {
  data: IdentityData
  setData(update: Partial<IdentityData>): void
}

interface HomePage extends PageBoundary {
  showContext(user: UserProfile, ledger: Ledger): void
  openProfile(): void
  openMembers(): void
  openInvites(): void
}

interface ProfilePage extends PageBoundary {
  loadProfile(): Promise<void>
}

const ledger: Ledger = {
  id: 1, name: '共享账本', currency: 'CNY', timezone: 'Asia/Shanghai', maxMembers: 10,
}

function userWithDisplayName(displayName: string | null): UserProfile {
  // The wire contract permits null; bootstrap does not set ledger_member.display_name.
  return {
    userId: 1, nickname: '微信用户', avatarUrl: null, ledgerId: 1,
    memberId: 1, role: 'OWNER', displayName,
  }
}

function instantiate<T extends PageBoundary>(definition: T): T {
  return {
    ...definition,
    data: { ...definition.data },
    setData(update: Partial<IdentityData>) { Object.assign(this.data, update) },
  }
}

let homeDefinition: HomePage
let profileDefinition: ProfilePage
const navigateTo = vi.fn()

beforeAll(async () => {
  let captured: unknown
  vi.stubGlobal('Page', (definition: unknown) => { captured = definition })
  vi.stubGlobal('wx', {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'develop' } }),
    navigateTo,
    removeStorageSync: () => {},
    request: () => { throw new Error('Unexpected network request in page test') },
  })
  await import('../../miniprogram/pages/home/index')
  homeDefinition = captured as HomePage
  await import('../../miniprogram/pages/profile/index')
  profileDefinition = captured as ProfilePage
})

afterAll(() => {
  getRuntime().session.clear()
  vi.unstubAllGlobals()
})

describe('成员显示名为空时的真实页面数据绑定', () => {
  it('首页管理入口随最新角色变化，普通成员不能进入邀请管理', () => {
    const page = instantiate(homeDefinition)
    page.showContext(userWithDisplayName(null), ledger)
    expect(page.data.canManageInvites).toBe(true)
    page.showContext({ ...userWithDisplayName(null), role: 'MEMBER' }, ledger)
    expect(page.data.canManageInvites).toBe(false)
  })
  it.each([null, '', '   '])('首页显示名为 %s 时仍显示昵称和所有者身份', displayName => {
    const page = instantiate(homeDefinition)
    page.showContext(userWithDisplayName(displayName), ledger)

    expect(page.data.displayName).toBe('微信用户')
    expect(page.data.memberRoleLabel).toBe('所有者')
    expect(page.data.errorMessage).toBe('')
  })

  it('保留已有成员显示名，不用昵称覆盖', () => {
    const page = instantiate(homeDefinition)
    page.showContext(userWithDisplayName('账本别名'), ledger)
    expect(page.data.displayName).toBe('账本别名')
  })

  it('昵称更新后再次展示首页使用最新昵称，不修改成员别名', () => {
    const page = instantiate(homeDefinition)
    const user = userWithDisplayName(null)
    page.showContext(user, ledger)
    page.showContext({ ...user, nickname: '新昵称' }, ledger)

    expect(page.data.displayName).toBe('新昵称')
    expect(user.displayName).toBeNull()
  })

  it('个人资料入口导航到已注册的资料页', () => {
    const page = instantiate(homeDefinition)
    page.showContext(userWithDisplayName(null), ledger)
    page.openProfile()
    expect(navigateTo).toHaveBeenCalledWith({ url: '/pages/profile/index' })
  })

  it.each([null, '', '   '])('资料页显示名为 %s 时同样展示昵称和真实角色', async displayName => {
    const user = userWithDisplayName(displayName)
    getRuntime().session.setContext(user, ledger)
    const page = instantiate(profileDefinition)
    await page.loadProfile()

    expect(page.data.displayName).toBe('微信用户')
    expect(page.data.memberRoleLabel).toBe('所有者')
    expect(page.data.loading).toBe(false)
    expect(page.data.errorMessage).toBe('')
    expect(getRuntime().session.getUser()?.displayName).toBe(displayName)
  })
})
