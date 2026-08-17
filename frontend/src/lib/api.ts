export interface SessionSummary {
  id: string
  agentRole: string
  status: string
  observabilityLevel: string
  effectiveModel: string
  inputTokens: number
  outputTokens: number
}

export interface TranscriptTurn {
  role: string
  content: string
  createdAt: string
}

export interface Exchange {
  id: string
  requestedModel: string
  effectiveModel: string
  inputTokens: number
  outputTokens: number
  recordingStatus: string
  recordingGapReason: string | null
  latencyMs: number
}

export interface SessionDetail extends SessionSummary {
  latestSystemPrompt: string
  latestSystemPromptRecordingStatus: string
}

export interface Transcript {
  sessionId: string
  turns: TranscriptTurn[]
  systemPrompt: string
  totalExchanges: number
  recordingStatus: string
  gaps: Array<{ exchangeId: string; reason: string; detail?: string }>
}

export interface Health {
  status: string
}

export interface ProviderRoute {
  id: string
  name: string
  protocol: string
  models: string[]
  configured: boolean
}

export interface InvocationSummary {
  id: string
  commandId: string
  status: string
  content: string
  assistantContent: string | null
  createdAt: string
  semanticCompletedAt: string | null
}

export interface Envelope<T> {
  data: T
  error: { code: string; message: string } | null
}

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(path, init)
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  const body = (await response.json()) as Envelope<T>
  if (body.error) throw new Error(body.error.message)
  return body.data
}

export const api = {
  sessions: () => request<{ content: SessionSummary[]; totalElements: number }>('/api/v1/sessions'),
  detail: (id: string) => request<SessionDetail>(`/api/v1/sessions/${id}`),
  transcript: (id: string) => request<Transcript>(`/api/v1/sessions/${id}/transcript`),
  exchanges: (id: string) => request<{ sessionId: string; content: Exchange[] }>(`/api/v1/sessions/${id}/exchanges`),
  invocations: (id: string) => request<{ sessionId: string; content: InvocationSummary[] }>(`/api/v1/sessions/${id}/invocations`),
  health: () => fetch('/actuator/health').then((response) => response.json() as Promise<{ status: string }>),
  invoke: (id: string, content: string) => request<{ assistantContent: string; status: string }>(`/api/v1/sessions/${id}/invocations`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ content, commandId: crypto.randomUUID() }),
  }),
}
