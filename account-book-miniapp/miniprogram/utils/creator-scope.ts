import type { SessionStore } from '../store/session'
import type { CreatorOption } from '../types/entry'

export function creatorScopeIdentity(session: SessionStore): string {
  const user = session.getUser()
  return `${session.getRevision()}:${user?.ledgerId ?? ''}:${user?.userId ?? ''}:${user?.role ?? ''}`
}

export function creatorOptions(items: CreatorOption[]): CreatorOption[] {
  return [{ userId: 0, displayName: '全部成员' }, ...items]
}
