import { useLettaClient } from "@/providers/LettaProvider"
import { foramtToSlug } from "@/utils/agent-name-prompt"
import { Letta } from "@letta-ai/letta-client"
import { useMutation, UseMutationOptions, useQueryClient } from "@tanstack/react-query"
import { getUseAgentStateKey } from "./use-agent"
import { getAgentsQueryKey } from "./use-agents"
export function useEditAgent(
  mutationOptions: UseMutationOptions<
    Letta.AgentState,
    Error,
    Letta.UpdateAgent & { id: string }
  > = {},
) {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation<Letta.AgentState, Error, Letta.UpdateAgent & { id: string }>({
    mutationFn: async (data: Letta.UpdateAgent & { id: string }) => {
      if (data.tags) {
        data.tags = data.tags.map(foramtToSlug)
      }

      if (data.name) {
        data.name = foramtToSlug(data.name)
      }

      return await lettaClient.agents.modify(data.id, {
        ...data,
      })
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
