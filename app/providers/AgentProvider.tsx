import { createContext, useContext } from "react"
import { create } from "zustand"

interface AgentState {
  agentId: string
  setAgentId: (id?: string) => void
}

export const useAgentStore = create<AgentState>((set) => ({
  agentId: "",
  setAgentId: (id?: string) => set({ agentId: id ?? "" }),
}))

// Create a React Context
const AgentContext = createContext<AgentState | undefined>(undefined)

export const AgentProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const store = useAgentStore()
  return <AgentContext.Provider value={store}>{children}</AgentContext.Provider>
}

// Custom hook to use the store
export const useAgentContext = () => {
  const context = useContext(AgentContext)
  if (!context) {
    throw new Error("useAgent must be used within an AgentProvider")
  }
  return context
}
