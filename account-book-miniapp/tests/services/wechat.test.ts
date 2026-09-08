import { describe, expect, it } from 'vitest'
import { getWechatCode } from '../../miniprogram/services/wechat'

describe('getWechatCode', () => {
  it('returns a non-empty one-time code', async () => {
    const code = await getWechatCode(options => options.success({ code: 'wx-code' }))

    expect(code).toBe('wx-code')
  })

  it('rejects a successful native response that contains no code', async () => {
    await expect(getWechatCode(options => options.success({ code: '' })))
      .rejects.toMatchObject({
        kind: 'INVALID_RESPONSE',
        code: 'WECHAT_CODE_MISSING',
      })
  })

  it('normalizes native login failure without exposing the native message', async () => {
    const error = await getWechatCode(options => {
      options.fail({ errMsg: 'native-sensitive-detail' })
    }).catch(reason => reason as Error)

    expect(error).toMatchObject({
      kind: 'NETWORK',
      code: 'WECHAT_LOGIN_FAILED',
      message: '无法获取微信登录凭证，请重试',
    })
    expect(JSON.stringify(error)).not.toContain('native-sensitive-detail')
  })

  it('normalizes a synchronous adapter failure', async () => {
    await expect(getWechatCode(() => {
      throw new Error('native-sensitive-detail')
    })).rejects.toMatchObject({
      kind: 'NETWORK',
      code: 'WECHAT_LOGIN_FAILED',
    })
  })
})
