import type { SessionStore } from '../store/session'
import type { CreatorOption } from '../types/entry'

export function creatorScopeIdentity(session: SessionStore): string {
  const user = session.getUser()
  return `${session.getRevision()}:${user?.ledgerId ?? ''}:${user?.userId ?? ''}:${user?.role ?? ''}`
}

export function defaultCreatorScope(session: SessionStore) {
  const user = session.getUser()
  return {
    createdBy: user?.role === 'OWNER' ? user.userId : 0,
    creatorName: user?.displayName?.trim() || user?.nickname?.trim() || '我',
  }
}

export function resolveCreatorSelection(session: SessionStore, items: CreatorOption[], requested: number) {
  const own = defaultCreatorScope(session)
  if (session.getUser()?.role !== 'OWNER') {
    return { ...own, creators: [] as CreatorOption[], creatorIndex: 0 }
  }
  const active = [...items]
  if (!active.some(item => item.userId === own.createdBy)) {
    active.push({ userId: own.createdBy, displayName: own.creatorName })
  }
  const creators = [{ userId: 0, displayName: '全部成员' }, ...active]
  const selected = creators.find(item => item.userId === requested)
    || creators.find(item => item.userId === own.createdBy)!
  return { createdBy: selected.userId, creatorName: selected.displayName, creators,
    creatorIndex: creators.findIndex(item => item.userId === selected.userId) }
}
