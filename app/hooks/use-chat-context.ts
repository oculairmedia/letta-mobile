import { useLettaClient } from "@/providers/LettaProvider"
import type Letta from "@letta-ai/letta-client"
import { useQuery } from "@tanstack/react-query"

export const getAgentChatContextKey = (agentId: string) => ["agentChatContext", agentId]

type AgentContextResponse = {
  contextWindowSizeMax: number
  contextWindowSizeCurrent: number
  numTokensSystem: number
  numTokensFunctionsDefinitions: number
  numTokensExternalMemorySummary: number
  numTokensMessages: number
}

const fetchContextFn = async (client: Letta, agentId: string) => {
  const response = await fetch(client.baseURL + `/v1/agents/${agentId}/context`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${client.apiKey}`,
    },
  })

  if (!response.ok) {
    throw new Error("Failed to fetch agent chat context")
  }

  const data = await response.json()

  return {
    contextWindowSizeMax: data.context_window_size_max,
    contextWindowSizeCurrent: data.context_window_size_current,
    numTokensSystem: data.num_tokens_system,
    numTokensFunctionsDefinitions: data.num_tokens_functions_definitions,
    numTokensExternalMemorySummary: data.num_tokens_external_memory_summary,
    numTokensMessages: data.num_tokens_messages,
  }
}

export function useAgentChatContext(agentId: string) {
  const { lettaClient } = useLettaClient()
  return useQuery<AgentContextResponse>({
    queryKey: getAgentChatContextKey(agentId),
    queryFn: () => fetchContextFn(lettaClient!, agentId),
    throwOnError: true,
    enabled: !!lettaClient && !!agentId,
    refetchInterval: 3000,
  })
}
