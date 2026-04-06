import { useLettaClient } from "@/providers/LettaProvider"
import { foramtToSlug } from "@/utils/agent-name-prompt"
import { AgentState, AgentUpdateParams } from "@letta-ai/letta-client/resources/agents/agents"
import { useMutation, UseMutationOptions, useQueryClient } from "@tanstack/react-query"
import { getUseAgentStateKey } from "./use-agent"
import { getAgentsQueryKey } from "./use-agents"

export function useEditAgent(
  mutationOptions?: UseMutationOptions<AgentState, Error, AgentUpdateParams & { id: string }>,
) {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()

  return useMutation<AgentState, Error, AgentUpdateParams & { id: string }>({
    mutationFn: async (data: AgentUpdateParams & { id: string }) => {
      const { id, ...updateData } = data
      if (updateData.tags) {
        updateData.tags = updateData.tags.map(foramtToSlug)
      }
      if (updateData.name) {
        updateData.name = foramtToSlug(updateData.name)
      }
      return await lettaClient.agents.update(id, updateData)
    },
    ...mutationOptions,
    onSuccess: async (...args) => {
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(args[0].id) })
      mutationOptions?.onSuccess?.(...args)
    },
    onError: (error, variables, context) => {
      console.error(error)
      mutationOptions?.onError?.(error, variables, context)
    },
  })
}
