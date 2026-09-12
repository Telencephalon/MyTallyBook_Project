import { getRuntime } from '../../runtime'
import type { EntryCreateIntent } from '../../flows/entry-flow'
import type { Account, Category, EntryType } from '../../types/catalog'
import { entryDraft, shanghaiToday } from '../../utils/bookkeeping'
import { pageGuard } from '../../utils/page-guard'
import { toErrorView } from '../../utils/presentation'
import { navigateToPage } from '../../utils/navigation'

Page({
  data: {
    entryType: 'EXPENSE' as EntryType,
    amount: '', categoryId: 0, categoryName: '', accountId: 0, accountName: '', entryDate: '', note: '', personName: '', favorPreset: false, lifePreset: false,
    categories: [] as Category[], accounts: [] as Account[],
    loading: false, busy: false, canRetry: false, canRetryRead: false, errorMessage: '', requestId: '',
  },
  _active: true, _generation: 0, _dictionaryGeneration: 0, _loadedRevision: -1,
  _intent: null as EntryCreateIntent | null,

  onLoad(query: Record<string, string | undefined> = {}) {
    this.setData({ favorPreset: query.preset === 'favor', lifePreset: query.preset === 'life' })
    this.setData({ entryDate: shanghaiToday() })
    try {
      this._intent = getRuntime().entries.newCreateIntent()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
    }
  },

  async onShow() {
    this._active = true
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ loading: false, busy: false, errorMessage: view.message, requestId: view.requestId })
      return
    }
    const revision = runtime.session.getRevision()
    if (this._loadedRevision !== -1 && revision !== this._loadedRevision) {
      this.disableDepartureWarning()
      this._intent?.abandon()
      this._intent = runtime.entries.newCreateIntent()
      this.setData({ entryType: 'EXPENSE', amount: '', categoryId: 0, categoryName: '', accountId: 0, accountName: '',
        entryDate: shanghaiToday(), note: '', personName: '', canRetry: false })
    }
    this._loadedRevision = revision
    const completed = this._intent?.completedResult() ?? null
    if (completed) {
      this._intent?.consumeCompletedResult()
      this.setData({ loading: false, busy: false, canRetry: false })
      this.disableDepartureWarning()
      navigateToPage('/pages/home/index')
      return
    }
    const pending = this._intent?.pendingResult() ?? null
    const uncertain = this._intent?.hasUncertainResult() ?? false
    this.setData({ loading: false, busy: pending !== null, canRetry: uncertain })
    if (pending) void this.observeIntent(pending, this._generation)
    else if (uncertain) {
      const generation = this._generation
      void this.enableDepartureWarning().then(protectedDeparture => {
        if (!protectedDeparture && this._active && generation === this._generation) {
          this.setData({ errorMessage: '离开提醒不可用，请留在本页重试或确认放弃。', requestId: '' })
        }
      })
    } else this.disableDepartureWarning()
    await this.loadDictionaries()
  },

  async loadDictionaries() {
    const generation = this._generation
    const dictionaryGeneration = ++this._dictionaryGeneration
    let runtime: ReturnType<typeof getRuntime>
    try {
      runtime = getRuntime()
    } catch (error) {
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId })
      return
    }
    const pageCurrent = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const current = () => pageCurrent() && dictionaryGeneration === this._dictionaryGeneration
    this.setData({ loading: true, canRetryRead: false, errorMessage: '', requestId: '' })
    try {
      await runtime.flow.refreshContext()
      if (!current()) return
      const [categories, accounts] = await Promise.all([
        runtime.catalog.categories(this.data.entryType, 'ACTIVE'),
        runtime.catalog.accounts('ACTIVE'),
      ])
      if (current()) {
        const defaultCategory = this.data.favorPreset
          ? categories.items.find(item => item.name === '人情')
          : this.data.lifePreset
            ? categories.items.find(item => item.name === '生活') || categories.items.find(item => item.name === '居住')
            : categories.items[0]
        const categoryId = this.data.categoryId || defaultCategory?.id || 0
        const defaultAccount = this.data.favorPreset ? accounts.items.find(item => item.name === '微信') : accounts.items[0]
        const accountId = this.data.accountId || defaultAccount?.id || 0
        this.setData({
        categories: categories.items,
        accounts: accounts.items,
        categoryId,
        categoryName: categories.items.find(item => item.id === categoryId)?.name || '',
        accountId,
        accountName: accounts.items.find(item => item.id === accountId)?.name || '',
        })
        this.setData({ canRetryRead: false })
      }
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      this.setData({ errorMessage: view.message, requestId: view.requestId, canRetryRead: true })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  onHide() { this._active = false; ++this._generation; ++this._dictionaryGeneration; this.setData({ loading: false, busy: false }) },
  onUnload() {
    this._active = false; ++this._generation; ++this._dictionaryGeneration
    this.disableDepartureWarning(); this.setData({ loading: false, busy: false })
  },
  formLocked() { return this.data.loading || this.data.busy || this.data.canRetry },
  async retryDictionaries() {
    if (!this.data.canRetryRead || this.data.loading || this.data.busy) return
    await this.loadDictionaries()
  },
  onAmountInput(event: WechatMiniprogram.Input) { if (!this.formLocked()) this.setData({ amount: event.detail.value }) },
  onPersonNameInput(event: WechatMiniprogram.Input) { if (!this.formLocked()) this.setData({ personName: event.detail.value }) },
  onNoteInput(event: WechatMiniprogram.Input) { if (!this.formLocked()) this.setData({ note: event.detail.value }) },
  onDateChange(event: WechatMiniprogram.PickerChange) { if (!this.formLocked()) this.setData({ entryDate: String(event.detail.value) }) },
  onCategoryChange(event: WechatMiniprogram.PickerChange) {
    if (!this.formLocked()) {
      const selected = this.data.categories[Number(event.detail.value)]
      this.setData({ categoryId: selected?.id ?? 0, categoryName: selected?.name ?? '' })
    }
  },
  onAccountChange(event: WechatMiniprogram.PickerChange) {
    if (!this.formLocked()) {
      const selected = this.data.accounts[Number(event.detail.value)]
      this.setData({ accountId: selected?.id ?? 0, accountName: selected?.name ?? '' })
    }
  },
  onTypeChange(event: WechatMiniprogram.PickerChange) {
    if (this.formLocked()) return
    this.setData({ entryType: Number(event.detail.value) === 0 ? 'EXPENSE' : 'INCOME', categoryId: 0, categoryName: '' })
    void this.loadDictionaries()
  },

  async onSubmit() {
    if (this.formLocked()) return
    let body
    try {
      body = entryDraft(this.data)
    } catch (error) {
      const view = toErrorView(error); this.setData({ errorMessage: view.message, requestId: view.requestId }); return
    }
    const runtime = getRuntime()
    const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const intent = this._intent ?? runtime.entries.newCreateIntent()
    this._intent = intent
    await this.finishIntent(() => intent.submit(body), intent, current, true)
  },

  async onRetry() {
    if (!this.data.canRetry || this.data.busy || !this._intent) return
    const runtime = getRuntime(); const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    const intent = this._intent
    await this.finishIntent(() => intent.retry(), intent, current, true)
  },

  async onAbandon() {
    if (this.data.busy) return
    const runtime = getRuntime(); const generation = this._generation
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    if (this._intent?.hasUncertainResult()) {
      const confirmed = await new Promise<boolean>(resolve => wx.showModal({
        title: '放弃本次新增', content: '结果可能已保存。建议先查询账单，仍要放弃并返回吗？',
        success: result => resolve(result.confirm), fail: () => resolve(false),
      }))
      if (!confirmed || !current()) return
    }
    if (!current()) return
    this._intent?.abandon()
    this.setData({ canRetry: false })
    this.disableDepartureWarning()
    wx.navigateBack()
  },

  async observeIntent(operation: Promise<import('../../types/entry').Entry>, generation: number) {
    const runtime = getRuntime()
    const current = pageGuard(runtime.session, generation, () => this._active, () => this._generation)
    if (!this._intent) return
    await this.finishIntent(() => operation, this._intent, current, false)
  },

  async finishIntent(
    start: () => Promise<import('../../types/entry').Entry>,
    intent: EntryCreateIntent,
    current: () => boolean,
    requireProtectionBeforeStart: boolean,
  ) {
    this.setData({ busy: true, errorMessage: '', requestId: '' })
    const submitRevision = getRuntime().session.getRevision()
    try {
      const protectedDeparture = await this.enableDepartureWarning()
      if (!current()) return
      if (!protectedDeparture) {
        this.setData({
          errorMessage: requireProtectionBeforeStart
            ? '无法开启离开提醒，账单尚未提交，请升级微信后重试。'
            : '离开提醒不可用，请留在本页等待提交完成。',
          requestId: '',
        })
        if (requireProtectionBeforeStart) return
      }
      const saved = await start()
      // A successful write is authoritative. Do not let a lifecycle update
      // after the request make the saved form reusable or leave it visible.
      // When the page is hidden, leave the completed result on the intent so
      // onShow() can reconcile it and switch to the details tab. A session
      // identity change invalidates the result and must never navigate.
      this.disableDepartureWarning()
      if (getRuntime().session.getRevision() !== submitRevision) return
      if (!current()) return
      intent.consumeCompletedResult() ?? saved
      navigateToPage('/pages/home/index')
    } catch (error) {
      if (!current()) return
      const view = toErrorView(error)
      const uncertain = intent.hasUncertainResult()
      this.setData({ errorMessage: view.message, requestId: view.requestId, canRetry: uncertain })
      if (!uncertain) this.disableDepartureWarning()
    } finally { if (current()) this.setData({ busy: false }) }
  },

  enableDepartureWarning(): Promise<boolean> {
    if (typeof wx.enableAlertBeforeUnload !== 'function') return Promise.resolve(false)
    return new Promise(resolve => {
      let settled = false
      const finish = (enabled: boolean) => {
        if (!settled) { settled = true; resolve(enabled) }
      }
      try {
        wx.enableAlertBeforeUnload({
          message: '账单提交结果可能未知，离开前请确认是否已保存。',
          success: () => finish(true), fail: () => finish(false),
        })
      } catch { finish(false) }
    })
  },

  disableDepartureWarning() {
    if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
  },
})
