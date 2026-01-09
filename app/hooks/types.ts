import type { Letta } from "@letta-ai/letta-client"

export interface BaseAppMessage {
  id: string
  date: Date
  name?: string
}

export interface AppSystemMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.SYSTEM_MESSAGE
  content: string
}

export interface AppAssistantMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.ASSISTANT_MESSAGE
  content: string
}

export interface AppUserMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.USER_MESSAGE
  content: string
}

export interface AppToolCallMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.TOOL_CALL_MESSAGE
  content: string
  toolName?: string
  toolCallId?: string
}

export interface AppToolReturnMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.TOOL_RETURN_MESSAGE
  content: string
  toolCallId?: string
  status?: "success" | "error"
  stdout?: string[]
  stderr?: string[]
}

export interface AppReasoningMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.REASONING_MESSAGE
  content: string
}

export type AppMessage =
  | AppSystemMessage
  | AppAssistantMessage
  | AppUserMessage
  | AppToolCallMessage
  | AppToolReturnMessage
  | AppReasoningMessage

export type LettaMessageUnion = Letta.LettaMessageUnion

export enum MESSAGE_TYPE {
  SYSTEM_MESSAGE = "system_message",
  ASSISTANT_MESSAGE = "assistant_message",
  USER_MESSAGE = "user_message",
  TOOL_CALL_MESSAGE = "tool_call_message",
  REASONING_MESSAGE = "reasoning_message",
  TOOL_RETURN_MESSAGE = "tool_return_message",
}

export enum ROLE_TYPE {
  USER = "user",
}

export type Context<T> = { params: Promise<T> }

export const useAssistantMessage = true
