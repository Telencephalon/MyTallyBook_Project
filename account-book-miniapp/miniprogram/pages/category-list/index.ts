import { getRuntime } from '../../runtime'
import type { Category, EntryType, ResourceStatus } from '../../types/catalog'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'

Page({
  data: {
    items: [] as Category[],
    entryType: '' as '' | EntryType,
    status: '' as '' | ResourceStatus,
    loading: false,
    busy: false,
    canManage: false,
    errorMessage: '',
    requestId: '',
  },
  _active: true,
  _generation: 0,
  _writeOperation: 0,

  async onShow() {
    this._active = true
    this.setData({ loading: false })
    if (this.data.busy) return
    const generation = ++this._generation
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, errorMessage: view.message, requestId: view.requestId, canManage: false })
      return
    }
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    this.setData({ loading: true, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const role = runtime.session.getUser()?.role
      this.setData({ canManage: role === 'OWNER' || role === 'ADMIN' })
      const result = await runtime.catalog.categories(
        this.data.entryType || undefined,
        this.data.status || undefined,
      )
      if (current()) this.setData({ items: result.items })
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId, canManage: false })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  async retry() {
    if (this.data.loading || this.data.busy) return
    await this.onShow()
  },

  onHide() {
    this._active = false
    ++this._generation
    this.setData({ loading: false })
  },

  onUnload() {
    this._active = false
    ++this._generation
    this.setData({ loading: false })
  },

  onTypeFilter(event: WechatMiniprogram.PickerChange) {
    this.setData({ entryType: ['', 'EXPENSE', 'INCOME'][Number(event.detail.value)] as '' | EntryType })
    void this.onShow()
  },

  onStatusFilter(event: WechatMiniprogram.PickerChange) {
    this.setData({ status: ['', 'ACTIVE', 'DISABLED'][Number(event.detail.value)] as '' | ResourceStatus })
    void this.onShow()
  },

  openCreate() {
    if (this.data.canManage && !this.data.loading && !this.data.busy) {
      wx.navigateTo({ url: '/pages/category-edit/index' })
    }
  },

  openEdit(event: WechatMiniprogram.TouchEvent) {
    const id = Number(event.currentTarget.dataset.id)
    if (this.data.canManage && this.data.items.some(item => item.id === id)) {
      wx.navigateTo({ url: '/pages/category-edit/index?id=' + id })
    }
  },

  async onDelete(event: WechatMiniprogram.TouchEvent) {
    if (!this.data.canManage || this.data.busy) return
    const id = Number(event.currentTarget.dataset.id)
    if (!this.data.items.some(item => item.id === id)) return
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
      return
    }
    const generation = this._generation
    const operation = ++this._writeOperation
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => pageCurrent()
    this.setData({ busy: true })
    try {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '删除分类',
        content: '仅从未被账单引用的分类可删除；已有历史请停用。',
        success: result => resolve(result.confirm),
        fail: () => resolve(false),
      }))
      if (!confirmed || !current()) return
      await runtime.catalog.deleteCategory(id)
      if (current()) {
        this.setData({ busy: false })
        await this.onShow()
      }
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
    } finally {
      if (operation === this._writeOperation) this.setData({ busy: false })
    }
  },
})
