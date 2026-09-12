import { TAB_ROUTES } from '../utils/navigation'

Component({
  data: {
    selected: -1,
    tabs: TAB_ROUTES.map((url, index) => ({ url, text: ['首页', '明细', '统计', '我的'][index] })),
  },
  lifetimes: {
    attached() { this.syncSelected() },
  },
  pageLifetimes: {
    show() { this.syncSelected() },
  },
  methods: {
    syncSelected() {
      const pages = getCurrentPages()
      const route = pages[pages.length - 1]?.route
      this.setData({ selected: this.data.tabs.findIndex(tab => tab.url === `/${route}`) })
    },
    switchTab(event: WechatMiniprogram.TouchEvent) {
      const index = Number(event.currentTarget.dataset.index)
      const tab = this.data.tabs[index]
      if (!tab || index === this.data.selected) return
      wx.switchTab({ url: tab.url })
    },
  },
})
