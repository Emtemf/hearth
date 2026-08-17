import type { SessionSummary } from '../../lib/api'

interface SessionRailProps {
  sessions: SessionSummary[]
  selectedId: string | null
  onSelect: (id: string) => void
}

export function SessionRail({ sessions, selectedId, onSelect }: SessionRailProps) {
  return <aside className="rail">
    <div className="rail-head"><span className="kicker">Live sessions</span><span className="count">{sessions.length.toString().padStart(2, '0')}</span></div>
    <div className="session-list">{sessions.map((session) => <button className={`session-card ${selectedId === session.id ? 'selected' : ''}`} key={session.id} onClick={() => onSelect(session.id)}>
      <span className={`dot ${session.status.toLowerCase()}`} />
      <span className="session-copy"><strong>{session.agentRole}</strong><small>{session.effectiveModel}</small><small>{session.inputTokens} in · {session.outputTokens} out</small></span>
      <span className="session-status">{session.status}</span>
    </button>)}</div>
  </aside>
}
