import { getRuntime } from '../../runtime'
import { AppError } from '../../types/error'
import { roleLabel, toErrorView } from '../../utils/presentation'
import { validateNickname } from '../../utils/validation'

Page({
  _active: true,
  _generation: 0,
  _saveOperation: 0,
  data: {
    loading: false,
    saving: false,
    nickname: '',
    originalNickname: '',
    avatarUrl: '',
    displayName: '',
    memberRoleLabel: '',
    errorMessage: '',
    requestId: '',
  },

  onLoad() {
    this._active = true
    ++this._generation
    void this.loadProfile()
  },

  onShow() {
    this._active = true
    ++this._generation
    this.setData({ loading: false, saving: false })
    void this.loadProfile()
  },

  onHide() {
    this._active = false
    ++this._generation
  },

  onUnload() {
    this._active = false
    ++this._generation
  },

  async loadProfile() {
    if (!this._active || this.data.loading) {
      return
    }

    const runtime = getRuntime()
    const generation = this._generation
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    const refresh = runtime.session.getUser() ? undefined : runtime.flow.refreshContext()
    const isContextCurrent = runtime.flow.captureContextGuard()
    const isCurrent = () => this._active && generation === this._generation && isContextCurrent()
    try {
      await refresh
      if (!isCurrent()) return
      const user = runtime.session.getUser()
      if (!user) {
        throw new AppError(
          'INVALID_RESPONSE',
          'SESSION_CONTEXT_MISSING',
          '登录状态不完整，请重新登录',
        )
      }
      this.setData({
        nickname: user.nickname,
        originalNickname: user.nickname,
        avatarUrl: user.avatarUrl ?? '',
        displayName: user.displayName?.trim() || user.nickname,
        memberRoleLabel: roleLabel(user.role),
      })
    } catch (error) {
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this.setData({
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (isCurrent()) this.setData({ loading: false })
    }
  },

  onNicknameInput(event: WechatMiniprogram.Input) {
    this.setData({
      nickname: event.detail.value,
      errorMessage: '',
      requestId: '',
    })
  },

  save() {
    void this.saveProfile()
  },

  async saveProfile() {
    if (!this._active || this.data.saving || this.data.loading) {
      return
    }

    const validation = validateNickname(this.data.nickname)
    if (!validation.valid) {
      this.setData({ errorMessage: validation.message, requestId: '' })
      return
    }

    const runtime = getRuntime()
    const generation = this._generation
    const operation = ++this._saveOperation
    const isContextCurrent = runtime.flow.captureContextGuard()
    const isOperationCurrent = () => this._active && generation === this._generation
      && operation === this._saveOperation
    const isCurrent = () => isOperationCurrent() && isContextCurrent()
    this.setData({ saving: true, errorMessage: '', requestId: '' })
    try {
      const user = await runtime.flow.updateNickname(validation.value, isCurrent)
      if (!isCurrent()) return
      this.setData({
        nickname: user.nickname,
        originalNickname: user.nickname,
      })
      wx.navigateBack()
    } catch (error) {
      if (!isCurrent()) return
      const errorView = toErrorView(error)
      this.setData({
        errorMessage: errorView.message,
        requestId: errorView.requestId,
      })
    } finally {
      if (isOperationCurrent()) {
        this.setData({
          saving: false,
          ...(!isContextCurrent() ? {
            errorMessage: '登录身份或权限已变化；昵称修改可能已生效，请返回首页刷新后确认资料',
            requestId: '',
          } : {}),
        })
      }
    }
  },
})
