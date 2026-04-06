import { useLettaClient } from "@/providers/LettaProvider"
import { Conversation } from "@letta-ai/letta-client/resources/conversations/conversations"
import { useMutation, useQuery, useQueryClient, UseQueryOptions } from "@tanstack/react-query"

export const getConversationsQueryKey = (agentId: string) => ["conversations", agentId]

export function useConversations(
  agentId: string,
  queryOptions?: Omit<UseQueryOptions<Conversation[]>, "queryKey" | "queryFn">,
) {
  const { lettaClient } = useLettaClient()
  return useQuery<Conversation[]>({
    queryKey: getConversationsQueryKey(agentId),
    queryFn: async () => {
      const conversations = await lettaClient.conversations.list({
        agent_id: agentId,
        order: "desc",
        order_by: "last_message_at",
      })
      return conversations
    },
    enabled: !!agentId && !!lettaClient,
    ...queryOptions,
  })
}

export function useCreateConversation() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async ({ agentId, summary }: { agentId: string; summary?: string }) => {
      return lettaClient.conversations.create({
        agent_id: agentId,
        summary,
      })
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: getConversationsQueryKey(data.agent_id),
      })
    },
  })
}

export function useDeleteConversation() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async ({
      conversationId,
      agentId,
    }: {
      conversationId: string
      agentId: string
    }) => {
      await lettaClient.conversations.delete(conversationId)
      return { conversationId, agentId }
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: getConversationsQueryKey(variables.agentId),
      })
    },
  })
}
