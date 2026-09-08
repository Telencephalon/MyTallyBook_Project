import type { SessionStore } from '../store/session'

export function pageGuard(
  session: SessionStore,
  generation: number,
  active: () => boolean,
  currentGeneration: () => number,
): () => boolean {
  const revision = session.getRevision()
  return () => active()
    && generation === currentGeneration()
    && revision === session.getRevision()
}
