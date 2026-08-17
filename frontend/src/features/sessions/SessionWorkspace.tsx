import { useState } from 'react'
import type { Exchange, SessionDetail, Transcript } from '../../lib/api'
import { useInvoke } from './mutations'

interface SessionWorkspaceProps {
  detail: SessionDetail
  transcript: Transcript
  exchanges: Exchange[]
}

export function SessionWorkspace({ detail, transcript, exchanges }: SessionWorkspaceProps) {
  const [content, setContent] = useState('')
  const invoke = useInvoke(detail.id)
  const input = exchanges.reduce((sum, item) => sum + item.inputTokens, 0)
  const output = exchanges.reduce((sum, item) => sum + item.outputTokens, 0)
  const submit = () => { const value = content.trim(); if (value) { invoke.mutate(value); setContent('') } }
  return <div className="workspace">
    <div className="workspace-top"><div><span className="kicker">Session / {detail.agentRole}</span><h1>{detail.effectiveModel}</h1><code>{detail.id}</code></div><div className="health-chip"><span className="pulse" /> {detail.status}</div></div>
    <div className="metrics"><Metric label="Input tokens" value={input.toLocaleString()} /><Metric label="Output tokens" value={output.toLocaleString()} /><Metric label="Exchanges" value={exchanges.length.toString()} /><Metric label="Recording" value={detail.latestSystemPromptRecordingStatus} /></div>
    <section className="panel transcript-panel"><div className="panel-title"><span>Conversation trace</span><span className="panel-meta">{transcript.turns.length} turns</span></div><div className="turns">{transcript.turns.map((turn, index) => <article className={`turn turn-${turn.role}`} key={`${turn.createdAt}-${index}`}><div className="turn-meta"><span>{turn.role.replace('_', ' ')}</span><time>{new Date(turn.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</time></div><p>{turn.content}</p></article>)}</div><div className="composer"><textarea value={content} onChange={(event) => setContent(event.target.value)} placeholder="Send an invocation to this session…" onKeyDown={(event) => { if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) submit() }} /><button onClick={submit} disabled={invoke.isPending || !content.trim()}>{invoke.isPending ? 'Running…' : 'Run invocation'}</button></div></section>
    <section className="panel prompt-panel"><div className="panel-title"><span>System prompt / evidence</span><span className="badge">{detail.observabilityLevel}</span></div><pre>{detail.latestSystemPrompt}</pre></section>
  </div>
}

function Metric({ label, value }: { label: string; value: string }) { return <div className="metric"><span>{label}</span><strong>{value}</strong></div> }
