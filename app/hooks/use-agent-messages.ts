import { useLettaClient } from "@/providers/LettaProvider"
import { useMutation, useQuery, useQueryClient, UseQueryOptions } from "@tanstack/react-query"
import { AppMessage, useAssistantMessage } from "./types"
import { filterMessages } from "./utils"

export const getAgentMessagesQueryKey = (agentId: string) => ["agentMessages", agentId]

export function useAgentMessages(agentId: string, queryOptions?: UseQueryOptions<AppMessage[]>) {
  const { lettaClient } = useLettaClient()
  return useQuery<AppMessage[]>({
    queryKey: getAgentMessagesQueryKey(agentId),
    queryFn: () =>
      lettaClient.agents.messages.list(agentId, { useAssistantMessage }).then(filterMessages),
    enabled: !!agentId && !!lettaClient,
    initialData: [],
    ...queryOptions,
  })
}

export function useResetChatMessages() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      agentId,
      addDefaultInitialMessages,
    }: {
      agentId: string
      addDefaultInitialMessages?: boolean
    }) => lettaClient.agents.messages.reset(agentId, { addDefaultInitialMessages }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentMessagesQueryKey(variables.agentId) })
    },
  })
}
