import { describe, expect, it } from 'vitest'

import type { MemberRole } from '../../miniprogram/types/api'
import { AppError } from '../../miniprogram/types/error'
import { roleLabel, toErrorView } from '../../miniprogram/utils/presentation'

describe('toErrorView', () => {
  it('保留可公开展示的业务错误和请求编号', () => {
    expect(
      toErrorView(
        new AppError(
          'HTTP',
          'BOOTSTRAP_NOT_CONFIGURED',
          '服务器尚未配置初始化口令',
          'req-1',
          503,
        ),
      ),
    ).toEqual({
      message: '服务器尚未配置初始化口令',
      requestId: 'req-1',
    })
  })

  it('不会把未知异常详情展示给用户', () => {
    expect(toErrorView(new Error('internal implementation detail'))).toEqual({
      message: '操作失败，请稍后重试',
      requestId: '',
    })
  })
})

describe('roleLabel', () => {
  it.each<[MemberRole, string]>([
    ['OWNER', '所有者'],
    ['ADMIN', '管理员'],
    ['MEMBER', '普通成员'],
  ])('将 %s 显示为 %s', (role, label) => {
    expect(roleLabel(role)).toBe(label)
  })
})
