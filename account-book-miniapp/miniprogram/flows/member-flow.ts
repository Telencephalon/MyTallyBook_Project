import type { MemberApi } from '../services/member'
import type { SessionStore } from '../store/session'
import type { SessionFlow } from './session-flow'
import { invalidateTabSnapshots } from '../utils/tab-snapshot'

export class MemberFlow {
  constructor(private readonly api: MemberApi, private readonly session: SessionStore, private readonly sessionFlow: SessionFlow) {}
  async list() {
    const members = await this.api.list()
    // A fresh member read can observe a departure performed on another phone.
    // Tabs must refresh creator choices when returning from the member page.
    invalidateTabSnapshots()
    return members
  }
  async changeRole(id: number, role: 'ADMIN' | 'MEMBER') {
    const member = await this.api.changeRole(id, role)
    await this.sessionFlow.refreshContext()
    return member
  }
  async remove(id: number) {
    const token = this.session.getToken()
    const result = await this.api.remove(id)
    if (result.status === 'LEFT') {
      if (this.session.getToken() === token) this.session.clear()
    }
    else await this.sessionFlow.refreshContext()
    return result
  }
  async transfer(id: number) {
    const result = await this.api.transfer(id)
    await this.sessionFlow.refreshContext()
    return result
  }
}
