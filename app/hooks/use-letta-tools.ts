import { useLettaClient } from "@/providers/LettaProvider"
import { AgentState } from "@letta-ai/letta-client/resources/agents/agents"
import { Tool } from "@letta-ai/letta-client/resources/tools"
import { useMutation, UseMutationOptions, useQuery, useQueryClient } from "@tanstack/react-query"
import { getUseAgentStateKey } from "./use-agent"
import { getAgentsQueryKey } from "./use-agents"
export const getLettaToolsQueryKey = () => ["letta-tools"]

export const useLettaTools = () => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getLettaToolsQueryKey(),
    queryFn: async () => {
      const page = await lettaClient.tools.list()
      const tools: Tool[] = []
      for await (const tool of page) {
        tools.push(tool)
      }
      return tools
    },
    enabled: !!lettaClient,
  })
}

export function useAttachToolToAgent({
  onSuccess,
  ...mutationOptions
}: UseMutationOptions<AgentState | null, Error, { agentId: string; toolId: string }> = {}) {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation<AgentState | null, Error, { agentId: string; toolId: string }>({
    mutationFn: ({ agentId, toolId }: { agentId: string; toolId: string }) =>
      lettaClient.agents.tools.attach(toolId, { agent_id: agentId }),
    onSuccess: (...args) => {
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(args[1].agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      onSuccess?.(...args)
    },
    ...mutationOptions,
  })
}

export function useDetachToolFromAgent({
  onSuccess,
  ...mutationOptions
}: UseMutationOptions<AgentState | null, Error, { agentId: string; toolId: string }> = {}) {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation<AgentState | null, Error, { agentId: string; toolId: string }>({
    mutationFn: ({ agentId, toolId }: { agentId: string; toolId: string }) =>
      lettaClient.agents.tools.detach(toolId, { agent_id: agentId }),
    onSuccess: (...args) => {
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(args[1].agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      onSuccess?.(...args)
    },
    ...mutationOptions,
  })
}
