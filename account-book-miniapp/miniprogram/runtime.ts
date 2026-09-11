import { getApiEnvironment } from './config/env'
import { SessionFlow } from './flows/session-flow'
import { InviteFlow } from './flows/invite-flow'
import { MemberFlow } from './flows/member-flow'
import { CatalogFlow } from './flows/catalog-flow'
import { EntryFlow } from './flows/entry-flow'
import { StatisticsFlow } from './flows/statistics-flow'
import { InviteApi } from './services/invite'
import { MemberApi } from './services/member'
import { CatalogApi } from './services/catalog'
import { EntryApi } from './services/entry'
import { StatisticsApi } from './services/statistics'
import { AuthApi } from './services/auth'
import { HttpClient, wechatRequestExecutor } from './services/http'
import { LedgerApi } from './services/ledger'
import { UserApi } from './services/user'
import { getWechatCode } from './services/wechat'
import { createSessionStore, type SessionStore } from './store/session'

export interface Runtime {
  session: SessionStore
  flow: SessionFlow
  invites: InviteFlow
  members: MemberFlow
  catalog: CatalogFlow
  entries: EntryFlow
  statistics: StatisticsFlow
}

let cachedRuntime: Runtime | null = null

export function getRuntime(): Runtime {
  if (cachedRuntime) {
    return cachedRuntime
  }

  const environment = getApiEnvironment()
  const session = createSessionStore(`${environment.envVersion ?? 'unknown'}|${environment.baseUrl}`)
  const http = new HttpClient({
    environment,
    getToken: () => session.getToken(),
    onUnauthorized: () => {
      session.clear()
      wx.reLaunch({ url: '/pages/login/index' })
    },
    executor: wechatRequestExecutor,
  })

  const flow = new SessionFlow(
    session,
    new AuthApi(http),
    new UserApi(http),
    new LedgerApi(http),
    () => getWechatCode(),
  )
  cachedRuntime = {
    session,
    flow,
    invites: new InviteFlow(new InviteApi(http), flow, () => getWechatCode()),
    members: new MemberFlow(new MemberApi(http), session, flow),
    catalog: new CatalogFlow(new CatalogApi(http)),
    entries: new EntryFlow(new EntryApi(http)),
    statistics: new StatisticsFlow(new StatisticsApi(http)),
  }
  return cachedRuntime
}
