import type { SessionStore } from '../store/session'
import { creatorScopeIdentity } from './creator-scope'

// Only a freshness stamp is shared; the rendered data stays on its own Page.
// Permission context is always refreshed before checking this stamp.
let dataRevision = 0
const FRESH_FOR_MS = 15_000

export interface TabSnapshot {
  identity: string
  query: string
  revision: number
  capturedAt: number
}

export function invalidateTabSnapshots(): void {
  ++dataRevision
}

export function captureTabSnapshot(session: SessionStore, query: string): TabSnapshot {
  return { identity: creatorScopeIdentity(session), query, revision: dataRevision, capturedAt: Date.now() }
}

export function matchesTabSnapshot(snapshot: TabSnapshot | null, session: SessionStore, query: string): boolean {
  return snapshot !== null && snapshot.identity === creatorScopeIdentity(session) && snapshot.query === query
}

export function canReuseTabSnapshot(snapshot: TabSnapshot | null, session: SessionStore, query: string): boolean {
  if (!snapshot || !matchesTabSnapshot(snapshot, session, query)) return false
  const age = Date.now() - snapshot.capturedAt
  return snapshot.revision === dataRevision && age >= 0 && age < FRESH_FOR_MS
}
