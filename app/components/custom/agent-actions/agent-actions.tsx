import { spacing } from "@/theme"
import { View, ViewStyle } from "react-native"
import { ResetChat } from "./reset-chat"
import { AgentActionsProps } from "./types"

export function AgentActions({ agentId, conversationId }: AgentActionsProps) {
  return (
    <View style={$container}>
      <ResetChat agentId={agentId} conversationId={conversationId} />
    </View>
  )
}

const $container: ViewStyle = {
  gap: spacing.md,
}
