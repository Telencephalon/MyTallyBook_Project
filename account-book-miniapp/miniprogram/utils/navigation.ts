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

export function returnToEntryDetail(id: number): void {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  let firstDetail = pages.length - 1
  // Reuse the original detail instance so its onShow refreshes the saved bill.
  // Also discard consecutive copies left by the previous edit/redirect flow.
  while (firstDetail > 0) {
    const previous = pages[firstDetail - 1]
    if (previous.route !== 'pages/entry-detail/index' || Number(previous.options.id) !== id) break
    firstDetail--
  }
  if (firstDetail < pages.length - 1) {
    wx.navigateBack({ delta: pages.length - 1 - firstDetail })
  } else {
    // A directly opened editor has no matching detail page to return to.
    wx.redirectTo({ url: `/pages/entry-detail/index?id=${id}` })
  }
}
