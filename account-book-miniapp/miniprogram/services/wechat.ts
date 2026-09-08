import { AppError } from '../types/error'

export interface WechatLoginOptions {
  success(result: { code: string }): void
  fail(error: { errMsg: string }): void
}

export type WechatLoginExecutor = (options: WechatLoginOptions) => void

const nativeWechatLogin: WechatLoginExecutor = options => {
  wx.login({
    success: result => options.success({ code: result.code }),
    fail: error => options.fail({ errMsg: error.errMsg }),
  })
}

export function getWechatCode(
  executor: WechatLoginExecutor = nativeWechatLogin,
): Promise<string> {
  return new Promise((resolve, reject) => {
    try {
      executor({
        success: result => {
          if (!result.code) {
            reject(new AppError(
              'INVALID_RESPONSE',
              'WECHAT_CODE_MISSING',
              '微信未返回登录凭证，请重试',
            ))
            return
          }
          resolve(result.code)
        },
        fail: () => reject(new AppError(
          'NETWORK',
          'WECHAT_LOGIN_FAILED',
          '无法获取微信登录凭证，请重试',
        )),
      })
    } catch {
      reject(new AppError(
        'NETWORK',
        'WECHAT_LOGIN_FAILED',
        '无法获取微信登录凭证，请重试',
      ))
    }
  })
}
