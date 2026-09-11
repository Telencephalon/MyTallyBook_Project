export const TAB_ROUTES = [
  '/pages/home/index',
  '/pages/entry-list/index',
  '/pages/statistics/index',
  '/pages/settings/index',
] as const

export function navigateToPage(url: string): void {
  if ((TAB_ROUTES as readonly string[]).includes(url)) {
    if (typeof wx.switchTab === 'function') wx.switchTab({ url })
    else wx.navigateTo({ url })
    return
  }
  wx.navigateTo({ url })
}

export function goHome(): void {
  navigateToPage('/pages/home/index')
}
