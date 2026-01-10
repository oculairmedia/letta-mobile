import { useMutation, useQueryClient } from "@tanstack/react-query"
import { AppMessage, MESSAGE_TYPE, ROLE_TYPE, use_assistant_message } from "./types"
import { getAgentMessagesQueryKey } from "./use-agent-messages"
import { extractMessage, getMessageId } from "./utils"

import { useLettaClient } from "@/providers/LettaProvider"
import { clientTools } from "@/utils/client-tools/registry"
import type Letta from "@letta-ai/letta-client"
import { Message } from "@letta-ai/letta-client/resources/agents/messages"
import uuid from "react-native-uuid"
const uuidv4 = uuid.v4

export interface UseSendMessageType {
  agentId: string
  text: string
}

function updateMessageInQueryData(
  queryClient: ReturnType<typeof useQueryClient>,
  agentId: string,
  response: Message,
  responseMessageId: string,
) {
  queryClient.setQueriesData<AppMessage[] | undefined>(
    {
      queryKey: getAgentMessagesQueryKey(agentId),
    },
    (_data) => {
      if (!_data) {
        _data = []
      }

      const data = _data.filter((message) => Boolean(message) && message.id !== "deleteme_")
      const processedMessage = extractMessage(response)

      if (!processedMessage) {
        return data
      }

      const existingMessage = data.find((message) => message.id === responseMessageId)

      if (existingMessage?.messageType === processedMessage.messageType) {
        return data.map((message) => {
          if (message.id === responseMessageId) {
            if (message.messageType === MESSAGE_TYPE.ASSISTANT_MESSAGE) {
              return {
                ...processedMessage,
                content: existingMessage.content + processedMessage.content,
              }
            }
            return processedMessage
          }
          return message
        })
      }

      const newData = [...data, processedMessage]

      // Sort messages with reasoning messages appearing before other messages with same timestamp
      return newData.sort((a, b) => {
        if (a.date.getTime() === b.date.getTime()) {
          if (a.messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
            return -1
          }
          if (b.messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
            return 1
          }
        }
        return a.date.getTime() - b.date.getTime()
      })
    },
  )
}

interface ProcessMessageStreamProps {
  messages: any[]
  agentId: string
  lettaClient: Letta
  queryClient: ReturnType<typeof useQueryClient>
}
async function processMessageStream({
  messages,
  agentId,
  lettaClient,
  queryClient,
}: ProcessMessageStreamProps) {
  if (!messages) return

  for (const response of messages) {
    const responseMessageId = getMessageId(response)
    updateMessageInQueryData(queryClient, agentId, response, responseMessageId)

    // Intercept Approval Request for Client Tools
    if (response.message_type === 'approval_request_message') {
      const toolCall = response.tool_call
      const clientToolFunc = clientTools[toolCall.name]

      if (clientToolFunc) {
        console.log(`[ClientTool] Intercepting tool call: ${toolCall.name}`)

        let toolReturn = ""
        let status = "success"

        try {
          toolReturn = await clientToolFunc(toolCall.arguments)
        } catch (e: any) {
          console.error(`[ClientTool] Execution failed:`, e)
          toolReturn = `Error executing client tool: ${e.message}`
          status = "error"
        }

        const nextResponse = await lettaClient.agents.messages.create(
          agentId,
          {
            messages: [
              {
                type: "approval",
                approvals: [
                  {
                    type: "tool",
                    tool_call_id: toolCall.tool_call_id,
                    tool_return: toolReturn,
                    status: status === "success" ? "success" : "error",
                  },
                ],
              },
            ],
          }
        ).catch(err => {
          console.error("[ClientTool] Failed to send approval response:", err)
          return null
        })

        if (nextResponse && nextResponse.messages) {
          await processMessageStream({
            messages: nextResponse.messages,
            agentId,
            lettaClient,
            queryClient,
          })
        }
      }
    }
  }
}

export function useSendMessageAsync() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()



  async function sendMessage(options: UseSendMessageType) {
    const { agentId, text } = options

    const userMessage: AppMessage = {
      id: uuidv4(),
      date: new Date(),
      messageType: MESSAGE_TYPE.USER_MESSAGE,
      content: text,
    }

    queryClient.setQueriesData<AppMessage[] | undefined>(
      {
        queryKey: getAgentMessagesQueryKey(agentId),
      },
      (_data) => {
        const data = _data ?? []
        return [...data, userMessage]
      },
    )

    const messagesResponse = await lettaClient.agents.messages
      .create(
        agentId,
        {
          use_assistant_message,
          messages: [
            {
              role: ROLE_TYPE.USER,
              content: text,
            },
          ],
        },
        {
          timeout: 120 * 1000
        }
      )
      .catch((error) => {
        console.warn("Error sending message:", error)
      })

    if (!messagesResponse) {
      return
    }

    await processMessageStream({
      messages: messagesResponse.messages,
      agentId,
      lettaClient,
      queryClient,
    })
  }

  return useMutation<void, undefined, UseSendMessageType>({
    mutationFn: (options) =>
      sendMessage(options).catch((error) => {
        console.error("Error sending message:", error)
        throw error
      }),
  })
}
