import { AppToolMessage } from "@/hooks/types"
import { spacing } from "@/theme"
import { View, ViewStyle } from "react-native"
import { ToolCallMessage } from "./tool-call-message"

interface ToolInteractionProps {
  message: AppToolMessage
  style?: ViewStyle
}

export function ToolInteraction({ message, style }: ToolInteractionProps) {
  return (
    <View style={[$container, style]}>
      {message.toolCalls.map((toolCall) => (
        <ToolCallMessage
          key={toolCall.id}
          toolName={toolCall.toolName}
          content={toolCall.args}
          toolReturn={toolCall.output}
          status={toolCall.status === "pending" ? undefined : toolCall.status}
          stdout={toolCall.stdout}
          stderr={toolCall.stderr}
        />
      ))}
    </View>
  )
}

const $container: ViewStyle = {
  flexDirection: "column",
  gap: spacing.xs,
}
