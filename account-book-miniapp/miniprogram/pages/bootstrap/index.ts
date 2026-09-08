import { getRuntime } from '../../runtime'
import { AppError } from '../../types/error'
import { toErrorView } from '../../utils/presentation'
import { validateBootstrapKey } from '../../utils/validation'

Page({
  data: {
    bootstrapKey: '',
    busy: false,
    errorMessage: '',
    requestId: '',
  },

  onBootstrapKeyInput(event: WechatMiniprogram.Input) {
    this.setData({
      bootstrapKey: event.detail.value,
      errorMessage: '',
      requestId: '',
    })
  },

  submit() {
    void this.submitBootstrap()
  },

  async submitBootstrap() {
    if (this.data.busy) {
      return
    }

    const validation = validateBootstrapKey(this.data.bootstrapKey)
    if (!validation.valid) {
      this.setData({ errorMessage: validation.message, requestId: '' })
      return
    }

    this.setData({ busy: true, errorMessage: '', requestId: '' })
    try {
      const result = await getRuntime().flow.bootstrap(validation.value)
      this.clearSecret()
      if (result.destination === 'HOME') {
        wx.reLaunch({ url: '/pages/home/index' })
      }
    } catch (error) {
      if (error instanceof AppError && error.code === 'ALREADY_INITIALIZED') {
        this.clearSecret()
        wx.reLaunch({ url: '/pages/login/index' })
        return
      }
      const errorView = toErrorView(error)
      this.setData({
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      this.setData({ busy: false })
    }
  },

  backToLogin() {
    this.clearSecret()
    wx.reLaunch({ url: '/pages/login/index' })
  },

  onUnload() {
    this.clearSecret()
  },

  clearSecret() {
    this.setData({ bootstrapKey: '' })
  },
})
