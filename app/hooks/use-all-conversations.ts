import { useLettaClient } from "@/providers/LettaProvider"
import { Conversation } from "@letta-ai/letta-client/resources/conversations/conversations"
import { useQuery, UseQueryOptions } from "@tanstack/react-query"

export const getAllConversationsQueryKey = () => ["allConversations"]

export function useAllConversations(
  queryOptions?: Omit<UseQueryOptions<Conversation[]>, "queryKey" | "queryFn">,
) {
  const { lettaClient } = useLettaClient()
  return useQuery<Conversation[]>({
    queryKey: getAllConversationsQueryKey(),
    queryFn: async () => {
      const conversations = await lettaClient.conversations.list({
        order: "desc",
        order_by: "last_message_at",
        limit: 100,
      })
      return conversations
    },
    enabled: !!lettaClient,
    ...queryOptions,
  })
}
