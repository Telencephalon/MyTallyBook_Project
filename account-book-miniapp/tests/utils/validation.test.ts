import { describe, expect, it } from 'vitest'
import {
  validateBootstrapKey,
  validateNickname,
} from '../../miniprogram/utils/validation'

describe('validateBootstrapKey', () => {
  it('trims and accepts 20 characters', () => {
    expect(validateBootstrapKey(' 12345678901234567890 ')).toEqual({
      valid: true,
      value: '12345678901234567890',
    })
  })

  it.each(['short', 'x'.repeat(257)])('rejects an out-of-range key', value => {
    expect(validateBootstrapKey(value)).toEqual({
      valid: false,
      message: '初始化口令长度必须为20至256个字符',
    })
  })
})

describe('validateNickname', () => {
  it('trims and accepts a nickname', () => {
    expect(validateNickname('  新昵称  ')).toEqual({
      valid: true,
      value: '新昵称',
    })
  })

  it.each(['   ', 'x'.repeat(65)])('rejects an out-of-range nickname', value => {
    expect(validateNickname(value)).toEqual({
      valid: false,
      message: '昵称长度必须为1至64个字符',
    })
  })
})
