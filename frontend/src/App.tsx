import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SessionRail } from './features/sessions/SessionRail'
import { SessionWorkspace } from './features/sessions/SessionWorkspace'
import { useSessionList, useSessionDetail, useTranscript, useExchanges, useInvocations, useHealth } from './features/sessions/queries'
import { useSessionUi } from './stores/session.store'
import './styles.css'

const queryClient = new QueryClient()

function ControlRoom() {
  const { selectedSessionId, setSelectedSessionId } = useSessionUi()
  const sessions = useSessionList()
  const selected = selectedSessionId ?? sessions.data?.content[0]?.id ?? null
  const detail = useSessionDetail(selected)
  const transcript = useTranscript(selected)
  const exchanges = useExchanges(selected)
  const invocations = useInvocations(selected)
  const health = useHealth()
  if (sessions.isLoading) return <div className="loading">Loading Hearth control room…</div>
  if (sessions.isError || !sessions.data) return <div className="loading">Unable to connect to Hearth API.</div>
  return <><header className="topbar"><div className="brand"><span className="brand-mark">H</span><span>HEARTH</span><small>CONTROL ROOM / M1</small></div><div className="top-status"><span className="status-item"><i className="status-led online" /> API {health.data?.status ?? '…'}</span><span className="status-item"><i className="status-led online" /> POSTGRES</span><span className="status-item"><i className="status-led warn" /> WORKER FIXTURE</span></div></header><main className="shell"><SessionRail sessions={sessions.data.content} selectedId={selected} onSelect={setSelectedSessionId} />{detail.data && transcript.data && exchanges.data ? <SessionWorkspace detail={detail.data} transcript={transcript.data} exchanges={exchanges.data.content} invocations={invocations.data?.content ?? []} /> : <div className="empty">Select a session to inspect its evidence.</div>}</main></>
}

export default function App() { return <QueryClientProvider client={queryClient}><ControlRoom /></QueryClientProvider> }
