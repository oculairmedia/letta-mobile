import { UseMutationOptions } from "@tanstack/react-query"

import { useQueryClient } from "@tanstack/react-query"

import { useAgentStore } from "@/providers/AgentProvider"
import { useLettaClient } from "@/providers/LettaProvider"
import { useMutation } from "@tanstack/react-query"
import { getUseAgentStateKey } from "./use-agent"
import { getAgentsQueryKey } from "./use-agents"
export function useDeleteAgent(
  mutationOptions?: UseMutationOptions<unknown, Error, { agentId: string }>,
) {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation<unknown, Error, { agentId: string }>({
    mutationFn: ({ agentId }: { agentId: string }) => lettaClient.agents.delete(agentId),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
    },
    onSuccess: (data, variables, context) => {
      queryClient.removeQueries({
        queryKey: getUseAgentStateKey(variables.agentId),
        exact: true,
      })
      if (useAgentStore.getState().agentId === variables.agentId) {
        useAgentStore.getState().setAgentId()
      }
      mutationOptions?.onSuccess?.(data, variables, context)
    },
    ...mutationOptions,
  })
}
