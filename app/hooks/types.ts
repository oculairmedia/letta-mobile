import { Message } from "@letta-ai/letta-client/resources/agents/messages"

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

export interface AppReasoningMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.REASONING_MESSAGE
  content: string
}

export interface AppToolCallInfo {
  id: string
  toolName: string
  args: string
  status: "pending" | "success" | "error"
  output?: string
  stdout?: string[]
  stderr?: string[]
}

export interface AppToolMessage extends BaseAppMessage {
  messageType: MESSAGE_TYPE.TOOL_MESSAGE
  content: string
  toolCalls: AppToolCallInfo[]
}

export type AppMessage =
  | AppSystemMessage
  | AppAssistantMessage
  | AppUserMessage
  | AppToolMessage
  | AppReasoningMessage

export type LettaMessageUnion = Message

export enum MESSAGE_TYPE {
  SYSTEM_MESSAGE = "system_message",
  ASSISTANT_MESSAGE = "assistant_message",
  USER_MESSAGE = "user_message",
  TOOL_MESSAGE = "tool_message",
  REASONING_MESSAGE = "reasoning_message",
}

export enum ROLE_TYPE {
  USER = "user",
}

export type Context<T> = { params: Promise<T> }

export const use_assistant_message = true
