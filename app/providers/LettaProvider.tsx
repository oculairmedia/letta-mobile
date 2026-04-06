import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import Letta from "@letta-ai/letta-client"
import { useMemo } from "react"
export const useLettaClient = () => {
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
  }, [activeConfigId])

  return { lettaClient: client! }
}
