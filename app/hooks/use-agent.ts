"use client"

import { useLettaClient } from "@/providers/LettaProvider"
import { Letta } from "@letta-ai/letta-client"
import {
  useMutation,
  UseMutationOptions,
  useQuery,
  useQueryClient,
  UseQueryOptions,
} from "@tanstack/react-query"
import { getAgentsQueryKey } from "./use-agents"

export const getUseAgentStateKey = (agentId: string) => ["agentState", agentId]

export function useAgent(
  agentId: string,
  queryOptions?: Omit<UseQueryOptions<Letta.AgentState>, "queryKey" | "queryFn">,
) {
  const { lettaClient } = useLettaClient()
  return useQuery<Letta.AgentState>({
    queryKey: getUseAgentStateKey(agentId),
    queryFn: () => lettaClient.agents.retrieve(agentId),
    enabled: !!lettaClient && !!agentId,
    refetchInterval: 3000,
    ...queryOptions,
  })
}

export function useModifyAgent(
  agentId: string,
  mutationOptions?: UseMutationOptions<Letta.AgentState, Error, Letta.UpdateAgent>,
) {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (state: Letta.UpdateAgent) => lettaClient.agents.modify(agentId, state),
    onSuccess: (data, variables, context) => {
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      mutationOptions?.onSuccess?.(data, variables, context)
    },
    ...mutationOptions,
  })
}
