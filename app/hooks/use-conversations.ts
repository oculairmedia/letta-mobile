import { useLettaClient } from "@/providers/LettaProvider"
import { Conversation } from "@letta-ai/letta-client/resources/conversations/conversations"
import { useMutation, useQuery, useQueryClient, UseQueryOptions } from "@tanstack/react-query"
import { Alert } from "react-native"
import { getAllConversationsQueryKey } from "./use-all-conversations"

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
    onError: (error: Error) => {
      Alert.alert("Error", `Failed to delete conversation: ${error.message}`)
    },
  })
}

export function useUpdateConversation() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async ({
      conversationId,
      agentId,
      summary,
    }: {
      conversationId: string
      agentId: string
      summary: string
    }) => {
      await lettaClient.conversations.update(conversationId, { summary })
      return { conversationId, agentId }
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: getConversationsQueryKey(variables.agentId),
      })
      // Also invalidate the all conversations query
      queryClient.invalidateQueries({
        queryKey: getAllConversationsQueryKey(),
      })
    },
    onError: (error: Error) => {
      Alert.alert("Error", `Failed to update conversation: ${error.message}`)
    },
  })
}

export function useForkConversation() {
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
      // Fork creates a new conversation with the same history
      const forked = await lettaClient.conversations.fork(conversationId)
      return { forked, agentId }
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: getConversationsQueryKey(variables.agentId),
      })
      queryClient.invalidateQueries({
        queryKey: getAllConversationsQueryKey(),
      })
    },
    onError: (error: Error) => {
      Alert.alert("Error", `Failed to fork conversation: ${error.message}`)
    },
  })
}
