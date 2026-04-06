import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import Letta from "@letta-ai/letta-client"
import { createContext, useContext, useMemo, ReactNode } from "react"

interface LettaClientContextValue {
  lettaClient: Letta | undefined
}

const LettaClientContext = createContext<LettaClientContextValue | undefined>(undefined)

interface LettaClientProviderProps {
  children: ReactNode
}

export function LettaClientProvider({ children }: LettaClientProviderProps) {
  const getActiveConfig = useLettaConfigStore((state) => state.getActiveConfig)
  const activeConfigId = useLettaConfigStore((state) => state.activeConfigId)

  const client = useMemo(() => {
    if (!activeConfigId) return undefined
    const config = getActiveConfig()
    if (!config) return undefined
    return new Letta({
      baseURL: config.serverUrl,
      apiKey: config.accessToken,
    })
  }, [activeConfigId, getActiveConfig])

  const value = useMemo(() => ({ lettaClient: client }), [client])

  return <LettaClientContext.Provider value={value}>{children}</LettaClientContext.Provider>
}

export const useLettaClient = () => {
  const context = useContext(LettaClientContext)
  if (context === undefined) {
    throw new Error("useLettaClient must be used within a LettaClientProvider")
  }
  return { lettaClient: context.lettaClient! }
}
