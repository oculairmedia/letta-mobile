import { useLettaClient } from "@/providers/LettaProvider"
import { useMutation, useQuery, useQueryClient, UseQueryOptions } from "@tanstack/react-query"
import { AppMessage } from "./types"
import { filterMessages } from "./utils"

export const getAgentMessagesQueryKey = (agentId: string, conversationId?: string | null) => [
  "agentMessages",
  agentId,
  conversationId ?? "default",
]

export function useAgentMessages(
  agentId: string,
  conversationId?: string | null,
  queryOptions?: UseQueryOptions<AppMessage[]>,
) {
  const { lettaClient } = useLettaClient()
  return useQuery<AppMessage[]>({
    queryKey: getAgentMessagesQueryKey(agentId, conversationId),
    queryFn: async () => {
      if (conversationId) {
        // Use conversations.messages.list for specific conversation
        const response = await lettaClient.conversations.messages.list(conversationId)
        return filterMessages(response.getPaginatedItems())
      }
      // Use agent messages endpoint for default conversation
      const response = await lettaClient.agents.messages.list(agentId)
      return filterMessages(response.getPaginatedItems())
    },
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
      conversationId: _conversationId,
      add_default_initial_messages,
    }: {
      agentId: string
      conversationId?: string | null
      add_default_initial_messages?: boolean
    }) => lettaClient.agents.messages.reset(agentId, { add_default_initial_messages }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: getAgentMessagesQueryKey(variables.agentId, variables.conversationId),
      })
    },
  })
}
