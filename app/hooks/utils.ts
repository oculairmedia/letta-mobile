import { Letta } from "@letta-ai/letta-client"
import { AssistantMessageContent } from "@letta-ai/letta-client/api/types/AssistantMessageContent"
import { AppMessage, MESSAGE_TYPE } from "./types"

export const getMessageId = (message: Letta.LettaMessageUnion): string => {
  if ("id" in message) {
    return message.messageType + message.id
  }

  return ""
}

export const extractMessageText = (message: AssistantMessageContent) => {
  if (typeof message === "string") {
    return message
  } else if (Array.isArray(message)) {
    return message
      .map((content) => {
        if (typeof content === "string") {
          return content
        }

        return content.text
      })
      .join(" ")
  }

  return ""
}

const isHeartbeatMessage = (message: string) => {
  try {
    const parsed = JSON.parse(message)
    if (parsed.type === "heartbeat") {
      return true
    }
    return null
  } catch {
    return null
  }
}

export function extractMessage(item: Letta.LettaMessageUnion): AppMessage | null {
  const { messageType } = item

  if (messageType === MESSAGE_TYPE.USER_MESSAGE) {
    if (!item.content) {
      return null
    }
    const message = extractMessageText(item.content)
    if (!message) {
      return null
    }
    if (isHeartbeatMessage(message)) {
      return null
    }
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: message,
      messageType: MESSAGE_TYPE.USER_MESSAGE,
    }
  }

  if (messageType === MESSAGE_TYPE.TOOL_CALL_MESSAGE) {
    if (!item.toolCall?.arguments) {
      return null
    }
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      toolName: item.toolCall.name,
      toolCallId: item.toolCall.toolCallId,
      content: item.toolCall.arguments,
      messageType: MESSAGE_TYPE.TOOL_CALL_MESSAGE,
    }
  }

  if (messageType === MESSAGE_TYPE.TOOL_RETURN_MESSAGE) {
    if (!item.toolReturn) {
      return null
    }
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      toolCallId: item.toolCallId,
      content: item.toolReturn,
      messageType: MESSAGE_TYPE.TOOL_RETURN_MESSAGE,
      status: item.status,
      stdout: item.stdout,
      stderr: item.stderr,
    }
  }

  if (messageType === MESSAGE_TYPE.ASSISTANT_MESSAGE) {
    if (!item.content) {
      return null
    }
    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: extractMessageText(item.content),
      messageType: MESSAGE_TYPE.ASSISTANT_MESSAGE,
    }
  }

  if (messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
    if (!item.reasoning) {
      return null
    }

    return {
      id: getMessageId(item),
      date: new Date(item.date),
      content: item.reasoning,
      messageType: MESSAGE_TYPE.REASONING_MESSAGE,
    }
  }

  return null
}

export function filterMessages(data: Letta.LettaMessageUnion[]): AppMessage[] {
  return data
    .map((item) => extractMessage(item))
    .filter(Boolean)
    .sort((a, b) => {
      // place reasoning_message always infront of the user message if they are in the same second
      if (a.date === b.date) {
        if (a.messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
          return -1
        }

        if (b.messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
          return 1
        }
      }

      // otherwise sort by date
      return a.date.getTime() - b.date.getTime()
    })
}
