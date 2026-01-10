import { useLettaClient } from "@/providers/LettaProvider"
import { useMutation, useQuery, useQueryClient, UseQueryOptions } from "@tanstack/react-query"
import { AppMessage, use_assistant_message } from "./types"
import { filterMessages } from "./utils"

export const getAgentMessagesQueryKey = (agentId: string) => ["agentMessages", agentId]

export function useAgentMessages(agentId: string, queryOptions?: UseQueryOptions<AppMessage[]>) {
  const { lettaClient } = useLettaClient()
  return useQuery<AppMessage[]>({
    queryKey: getAgentMessagesQueryKey(agentId),
    queryFn: () =>
      lettaClient.agents.messages
        .list(agentId)
        .then((response) => filterMessages(response.getPaginatedItems())),
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
      add_default_initial_messages,
    }: {
      agentId: string
      add_default_initial_messages?: boolean
    }) => lettaClient.agents.messages.reset(agentId, { add_default_initial_messages }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentMessagesQueryKey(variables.agentId) })
    },
  })
}
