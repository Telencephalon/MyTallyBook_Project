export interface AppInfo {
  appName: string
  version: string
  operator: string
  contact: string
  filingNumber: string
  filingStatus: '备案中'
  filingQueryUrl: string
}

export const APP_INFO: Readonly<AppInfo> = {
  appName: '随手账',
  version: '1.0.0',
  operator: '',
  contact: '',
  filingNumber: '',
  filingStatus: '备案中',
  filingQueryUrl: 'https://beian.miit.gov.cn',
}
