import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

interface AppConfig {
  pages: string[]
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
  it('保留已有入口并注册邀请、成员、记账字典与收支账单页面', () => {
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

  it('运行路由不包含 quickstart 示例页面', () => {
    expect(config.pages).not.toContain('pages/index/index')
    expect(config.pages).not.toContain('pages/logs/logs')
  })
})
