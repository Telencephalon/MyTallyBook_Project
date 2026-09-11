import { getRuntime } from '../../runtime'
import type { MemberView } from '../../types/api'
import { AppError } from '../../types/error'
import { roleLabel, toErrorView } from '../../utils/presentation'

type Action = 'role' | 'remove' | 'transfer'
Page({
  data: {
    target: null as MemberView | null, name: '', roleName: '', loading: false, busy: false,
    canChangeRole: false, canRemove: false, canTransfer: false,
    errorMessage: '', requestId: '',
  },
  _memberId: 0,
  _identity: '',
  _alive: true,
  _generation: 0,
  _loadGeneration: -1,
  _mutationOperation: 0,
  onLoad(query: { memberId?: string }) {
    const id = Number(query.memberId)
    this._memberId = Number.isSafeInteger(id) && id > 0 ? id : 0
  },
  async onShow() { this._alive = true; ++this._generation; this.setData({ loading: false }); await this.loadMember() },
  onUnload() { this._alive = false; ++this._generation },
  identity() {
    const session = getRuntime().session
    return session.getUser() && session.getToken() ? session.getUser()!.userId + ':' + session.getToken() : ''
  },
  permissions() {
    let user: ReturnType<ReturnType<typeof getRuntime>['session']['getUser']>
    try {
      user = getRuntime().session.getUser()
    } catch {
      this.setData({ canChangeRole: false, canRemove: false, canTransfer: false })
      return
    }
    const target = this.data.target
    const other = !!user && !!target && user.userId !== target.userId && this.identity() === this._identity
    this.setData({
      canChangeRole: other && user?.role === 'OWNER' && target?.role !== 'OWNER',
      canRemove: other && (user?.role === 'OWNER' || user?.role === 'ADMIN') && target?.role === 'MEMBER',
      canTransfer: other && user?.role === 'OWNER' && target?.role !== 'OWNER',
    })
  },
  async loadMember() {
    const generation = this._generation
    if (this.data.loading && this._loadGeneration === generation) return
    this._loadGeneration = generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      this.showError(error, () => this._alive && generation === this._generation)
      return
    }
    const revision = runtime.session.getRevision()
    const initialIdentity = this.identity()
    const current = () => this._alive && generation === this._generation
      && revision === runtime.session.getRevision()
      && initialIdentity === this.identity()
    this.setData({ loading: true, canChangeRole: false, canRemove: false, canTransfer: false, errorMessage: '', requestId: '' })
    try {
      if (!this._memberId) throw new AppError('INVALID_RESPONSE', 'CLIENT_VALIDATION_FAILED', '成员编号无效')
      await runtime.flow.refreshContext()
      if (!current()) return
      const identity = this.identity()
      const result = await runtime.members.list()
      if (!current() || !identity) return
      const target = result.items.find(item => item.memberId === this._memberId)
      if (!target) throw new AppError('INVALID_RESPONSE', 'MEMBER_STATE_CHANGED', '该成员已不在当前有效成员列表中')
      this._identity = identity
      this.setData({ target, name: target.displayName?.trim() || target.nickname, roleName: roleLabel(target.role) })
      this.permissions()
    } catch (error) {
      if (!current()) return
      this.setData({ target: null })
      this.showError(error, current)
    } finally { if (current()) this.setData({ loading: false }) }
  },
  showError(error: unknown, current: () => boolean = () => this._alive) {
    if (!current()) return
    const view = toErrorView(error)
    this.setData({ errorMessage: view.message, requestId: view.requestId })
    this.permissions()
  },
  allowed(action: Action) {
    this.permissions()
    return this._alive && !this.data.loading && (action === 'role' ? this.data.canChangeRole : action === 'remove' ? this.data.canRemove : this.data.canTransfer)
  },
  async onChangeRole() { await this.mutate('role') },
  async onRemove() { await this.mutate('remove') },
  async onTransfer() { await this.mutate('transfer') },
  async mutate(action: Action) {
    if (this.data.busy || !this.allowed(action) || !this.data.target) return
    const identity = this.identity()
    const revision = getRuntime().session.getRevision()
    const generation = this._generation
    const operation = ++this._mutationOperation
    const target = this.data.target
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    const nextRole = target.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'
    try {
      const content = action === 'transfer'
        ? '将账本所有权转让给此成员后，你将成为普通成员（MEMBER），失去管理权限，你创建的未使用且未过期邀请也会撤销。'
        : action === 'remove' ? '移除此成员后，对方的所有会话失效；历史记录保留，重新加入需要新的邀请。'
          : nextRole === 'MEMBER' ? '降为普通成员后，对方失去管理权限，其未使用且未过期邀请将撤销。' : '设为管理员后，对方可创建邀请和移除普通成员。'
      const confirmed = await new Promise<boolean>((resolve, reject) => wx.showModal({
        title: action === 'transfer' ? '转让所有权' : action === 'remove' ? '移除成员' : '修改角色',
        content, confirmText: '确认操作', success: result => resolve(result.confirm), fail: reject,
      }))
      if (this._alive && generation === this._generation) this.permissions()
      if (!confirmed || generation !== this._generation || identity !== this.identity()
        || revision !== getRuntime().session.getRevision() || this.data.target?.memberId !== target.memberId
        || this.data.target?.userId !== target.userId || this.data.target?.role !== target.role || !this.allowed(action)) return
      const flow = getRuntime().members
      if (action === 'role') await flow.changeRole(target.memberId, nextRole)
      else if (action === 'transfer') await flow.transfer(target.memberId)
      else {
        await flow.remove(target.memberId)
        if (this._alive) wx.redirectTo({ url: '/pages/member-list/index' })
        return
      }
      await this.loadMember()
    } catch (error) {
      const current = () => this._alive && generation === this._generation
        && identity === this.identity() && revision === getRuntime().session.getRevision()
        && target.memberId === this.data.target?.memberId
        && target.userId === this.data.target?.userId
        && operation === this._mutationOperation
      this.showError(error, current)
    }
    finally {
      if (operation === this._mutationOperation) this.setData({ busy: false })
    }
  },
})
