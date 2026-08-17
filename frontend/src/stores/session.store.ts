import { create } from 'zustand'

interface SessionUiState {
  selectedSessionId: string | null
  setSelectedSessionId: (id: string) => void
}

export const useSessionUi = create<SessionUiState>((set) => ({
  selectedSessionId: null,
  setSelectedSessionId: (selectedSessionId) => set({ selectedSessionId }),
}))
