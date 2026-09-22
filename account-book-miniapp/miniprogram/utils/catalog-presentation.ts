import type { Account, AccountType, Category } from '../types/catalog'

const categorySymbols: Record<string, { symbol: string; tone: string }> = {
  home: { symbol: '🏠', tone: 'mint' },
  salary: { symbol: '💰', tone: 'amber' },
  transport: { symbol: '🚌', tone: 'peach' },
  medical: { symbol: '✚', tone: 'blue' },
  gift: { symbol: '♥', tone: 'violet' },
  food: { symbol: '🍜', tone: 'peach' },
  shopping: { symbol: '🛍', tone: 'violet' },
  other: { symbol: '▦', tone: 'slate' },
}

export function categoryCard(item: Category) {
  const key = /生活|日常|居家/.test(item.name) ? 'home'
    : /工资|薪|奖金/.test(item.name) ? 'salary'
    : /交通|出行/.test(item.name) ? 'transport'
    : /医疗|健康/.test(item.name) ? 'medical'
    : /人情|礼/.test(item.name) ? 'gift'
    : /餐|饮食|食品/.test(item.name) ? 'food'
    : /购物|服饰/.test(item.name) ? 'shopping' : 'other'
  const appearance = categorySymbols[item.icon || ''] || categorySymbols[key]
  return { ...item, ...appearance }
}

const accountStyles: Record<AccountType, { symbol: string; tone: string; typeLabel: string }> = {
  WECHAT: { symbol: '微', tone: 'wechat', typeLabel: '微信' },
  ALIPAY: { symbol: '支', tone: 'alipay', typeLabel: '支付宝' },
  BANK: { symbol: '卡', tone: 'bank', typeLabel: '银行卡' },
  CASH: { symbol: '¥', tone: 'cash', typeLabel: '现金' },
  OTHER: { symbol: '账', tone: 'other', typeLabel: '其他账户' },
}

export function accountCard(item: Account) {
  return { ...item, ...(accountStyles[item.accountType] || accountStyles.OTHER),
    negativeBalance: item.currentBalance.startsWith('-') }
}
