import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'
import { TAB_ROUTES } from '../../miniprogram/utils/navigation'

interface AppConfig {
  pages: string[]
  tabBar: {
    list: Array<{ pagePath: string; text: string }>
  }
  window: {
    navigationBarTitleText: string
  }
}

const testsDirectory = dirname(fileURLToPath(import.meta.url))
const miniProgramDirectory = join(testsDirectory, '..', '..', 'miniprogram')
const config = JSON.parse(
  readFileSync(join(miniProgramDirectory, 'app.json'), 'utf8'),
) as AppConfig

describe('小程序入口配置', () => {
  it('保留已有入口并注册邀请、成员、记账字典、收支账单与公开说明页面', () => {
    expect(config.pages).toEqual([
      'pages/login/index',
      'pages/bootstrap/index',
      'pages/home/index',
      'pages/profile/index',
      'pages/invite-create/index',
      'pages/invite-accept/index',
      'pages/member-list/index',
      'pages/member-edit/index',
      'pages/category-list/index',
      'pages/category-edit/index',
      'pages/account-list/index',
      'pages/account-edit/index',
      'pages/entry-list/index',
      'pages/entry-create/index',
      'pages/entry-detail/index',
      'pages/entry-edit/index',
      'pages/statistics/index',
      'pages/settings/index',
      'pages/about/index',
      'pages/privacy/index',
    ])
    expect(config.window.navigationBarTitleText).toBe('随手账')
  })

  it('每个已注册页面都包含完整的原生页面文件', () => {
    for (const route of config.pages) {
      for (const extension of ['ts', 'wxml', 'wxss', 'json']) {
        expect(existsSync(join(miniProgramDirectory, `${route}.${extension}`))).toBe(true)
      }
    }
  })

  it('tabBar 元数据与运行时 TAB_ROUTES 保持一致', () => {
    expect(config.tabBar?.list?.map(item => `/${item.pagePath}`)).toEqual(TAB_ROUTES)
    expect(config.tabBar?.list?.map(item => item.text)).toEqual(['首页', '明细', '统计', '我的'])
  })

  it('运行路由不包含 quickstart 示例页面', () => {
    expect(config.pages).not.toContain('pages/index/index')
    expect(config.pages).not.toContain('pages/logs/logs')
  })
})
