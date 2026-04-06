import {
  AssistantMessage,
  Message,
  ReasoningMessage,
  ToolCallMessage,
  UserMessage,
} from "@letta-ai/letta-client/resources/agents/messages"
import { ToolReturnMessage } from "@letta-ai/letta-client/resources/tools"
import { AppMessage, AppToolMessage, MESSAGE_TYPE } from "./types"

export const getMessageId = (message: Message): string => {
  if ("id" in message) {
    return (message.message_type || "") + message.id
  }
  return ""
}

export const extractMessageText = (
  content: UserMessage["content"] | AssistantMessage["content"],
): string => {
  if (typeof content === "string") {
    return content
  } else if (Array.isArray(content)) {
    return content
      .map((c) => {
        if (typeof c === "string") {
          return c
        }
        if ("type" in c && c.type === "text") {
          return c.text
        }
        return ""
      })
      .filter(Boolean)
      .join(" ")
  }
  return ""
}

const isInternalMessage = (content: any) => {
  try {
    if (typeof content !== "string") return false
    const parsed = JSON.parse(content)
    return ["login", "system_alert", "heartbeat"].includes(parsed.type)
  } catch {
    return false
  }
}

export function extractMessage(item: Message): AppMessage | null {
  // We prefer filterMessages logic, but if used individually:
  const { message_type } = item

  if (message_type === "user_message") {
    const userItem = item as UserMessage
    if (!userItem.content) return null
    const message = extractMessageText(userItem.content)
    if (!message || isInternalMessage(message)) return null

    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: message,
      messageType: MESSAGE_TYPE.USER_MESSAGE,
    }
  }

  if (message_type === "assistant_message") {
    const assistantItem = item as AssistantMessage
    if (!assistantItem.content) return null
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: extractMessageText(assistantItem.content),
      messageType: MESSAGE_TYPE.ASSISTANT_MESSAGE,
    }
  }

  if (message_type === "system_message") {
    // System messages are typically internal but can be shown if needed.
    // Docs say "system messages are never streamed back in responses; they’re only visible when paginating".
    // We can map them if we want to show them, or ignore. Let's map for completeness but UI might hide.
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: item.content || "",
      messageType: MESSAGE_TYPE.SYSTEM_MESSAGE,
    }
  }

  if (message_type === "reasoning_message") {
    const reasoningItem = item as ReasoningMessage
    if (!reasoningItem.reasoning) return null
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: reasoningItem.reasoning,
      messageType: MESSAGE_TYPE.REASONING_MESSAGE,
    }
  }

  if (
    message_type === "hidden_reasoning_message" ||
    message_type === "tool_call_message" ||
    message_type === "tool_return_message"
  ) {
    return null
  }

  return null
}

export function filterMessages(data: Message[]): AppMessage[] {
  const sortedData = data
    .slice()
    .sort((a, b) => new Date(a.date).getTime() - new Date(b.date).getTime())

  return sortedData
    .reduce((acc, item) => {
      const { message_type } = item

      if (message_type === "tool_call_message") {
        const toolCallItem = item as ToolCallMessage
        if (
          !toolCallItem.tool_calls ||
          !Array.isArray(toolCallItem.tool_calls) ||
          toolCallItem.tool_calls.length === 0
        ) {
          return acc
        }

        const toolCalls = toolCallItem.tool_calls.map((tc) => {
          return {
            id: tc.tool_call_id || "",
            toolName: tc.name || "",
            args: tc.arguments || "",
            status: "pending" as const,
            output: undefined,
            stdout: undefined,
            stderr: undefined,
          }
        })

        const toolMessage: AppToolMessage = {
          id: getMessageId(item),
          date: new Date(item.date),
          messageType: MESSAGE_TYPE.TOOL_MESSAGE,
          content: "Tool Call",
          toolCalls,
        }
        acc.push(toolMessage)
        return acc
      }

      if (message_type === "tool_return_message") {
        const toolReturnItem = item as unknown as ToolReturnMessage

        let returnsToProcess: {
          toolCallId: string
          content: string
          status: "success" | "error"
          stdout?: string[]
          stderr?: string[]
        }[] = []

        if (
          toolReturnItem.tool_returns &&
          Array.isArray(toolReturnItem.tool_returns) &&
          toolReturnItem.tool_returns.length > 0
        ) {
          returnsToProcess = toolReturnItem.tool_returns.map((tr) => ({
            toolCallId: tr.tool_call_id,
            content: tr.tool_return || "",
            status: tr.status === "error" ? "error" : "success",
            stdout: tr.stdout || undefined,
            stderr: tr.stderr || undefined,
          }))
        }

        if (returnsToProcess.length === 0) return acc

        returnsToProcess.forEach((ret) => {
          for (let i = acc.length - 1; i >= 0; i--) {
            const msg = acc[i]
            if (msg.messageType === MESSAGE_TYPE.TOOL_MESSAGE) {
              const toolIndex = msg.toolCalls.findIndex((tc) => tc.id === ret.toolCallId)
              if (toolIndex !== -1) {
                msg.toolCalls[toolIndex].status = ret.status
                msg.toolCalls[toolIndex].output = ret.content
                msg.toolCalls[toolIndex].stdout = ret.stdout
                msg.toolCalls[toolIndex].stderr = ret.stderr
                break
              }
            }
          }
        })

        return acc
      }

      const msg = extractMessage(item)
      if (msg) {
        acc.push(msg)
      }

      return acc
    }, [] as AppMessage[])
    .sort((a, b) => {
      // place reasoning_message always infront of the user message if they are in the same second
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
}
