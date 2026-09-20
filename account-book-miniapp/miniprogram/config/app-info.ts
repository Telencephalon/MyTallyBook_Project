export interface AppInfo {
  appName: string
  version: string
  operator: string
  contact: string
  filingNumber: string
  filingStatus: '备案中' | '已备案'
  filingQueryUrl: string
}

export const APP_INFO: Readonly<AppInfo> = {
  appName: '随手账',
  version: '1.0.0',
  operator: 'Gitta',
  contact: '739817235@qq.com',
  filingNumber: '冀ICP备2026035005号-1X',
  filingStatus: '已备案',
  filingQueryUrl: 'https://beian.miit.gov.cn',
}
