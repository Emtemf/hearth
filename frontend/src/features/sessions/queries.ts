import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'

export const useSessionList = () => useQuery({ queryKey: ['sessions'], queryFn: api.sessions, refetchInterval: 5000 })
export const useSessionDetail = (id: string | null) => useQuery({ enabled: Boolean(id), queryKey: ['session', id], queryFn: () => api.detail(id ?? '') })
export const useTranscript = (id: string | null) => useQuery({ enabled: Boolean(id), queryKey: ['transcript', id], queryFn: () => api.transcript(id ?? '') })
export const useExchanges = (id: string | null) => useQuery({ enabled: Boolean(id), queryKey: ['exchanges', id], queryFn: () => api.exchanges(id ?? '') })
export const useInvocations = (id: string | null) => useQuery({ enabled: Boolean(id), queryKey: ['invocations', id], queryFn: () => api.invocations(id ?? ''), refetchInterval: 2000 })
export const useHealth = () => useQuery({ queryKey: ['health'], queryFn: api.health, refetchInterval: 10000 })
