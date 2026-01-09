import { Screen } from "@/components"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { AgentTools } from "@/shared/components/agent-settings"
import { spacing } from "@/theme"
import { AgentTabScreenProps } from "@/navigators"
import { FC } from "react"
import { View, ViewStyle } from "react-native"

interface ToolsScreenProps extends AgentTabScreenProps<"Tools"> {}

export const ToolsScreen: FC<ToolsScreenProps> = function ToolsScreen() {
  useLettaHeader()

  return (
    <Screen style={$root} preset="scroll">
      <View style={$container}>
        <AgentTools />
      </View>
    </Screen>
  )
}

const $root: ViewStyle = {
  flex: 1,
  padding: spacing.md,
}

const $container: ViewStyle = {
  gap: spacing.xs,
}
