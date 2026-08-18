import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../../lib/api'

export const useInvoke = (sessionId: string | null) => {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (content: string) => api.invoke(sessionId ?? '', content),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sessions'] }),
        queryClient.invalidateQueries({ queryKey: ['transcript', sessionId] }),
        queryClient.invalidateQueries({ queryKey: ['exchanges', sessionId] }),
      ])
    },
  })
}
