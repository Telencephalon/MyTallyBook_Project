import { APP_INFO } from '../../config/app-info'

Page({
  data: {
    appName: APP_INFO.appName,
    version: APP_INFO.version,
    operatorText: APP_INFO.operator || '待配置',
    contactText: APP_INFO.contact || '待配置',
    filingNumberText: APP_INFO.filingNumber || '待配置',
    filingText: APP_INFO.filingStatus,
    filingQueryUrl: APP_INFO.filingQueryUrl,
  },

  copyFilingQueryUrl() {
    wx.setClipboardData({ data: APP_INFO.filingQueryUrl })
  },
})
