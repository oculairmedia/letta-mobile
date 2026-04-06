import { Screen } from "@/components"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { Text } from "@/components/Text"
import { useAgent } from "@/hooks/use-agent"
import { useAgentId } from "@/hooks/use-agentId-param"
import { useDeleteAgent } from "@/hooks/use-delete-agent"
import { AgentTabScreenProps, navigate } from "@/navigators"
import {
  AgentBlocks,
  AgentConfig,
  AgentDescription,
  AgentEnvVars,
  AgentName,
  AgentSystemPrompt,
  AgentTags,
} from "@/shared/components/agent-settings"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FC } from "react"
import { Alert, View, ViewStyle } from "react-native"

interface AgentSettingsScreenProps extends AgentTabScreenProps<"Settings"> {}

export const AgentSettingsScreen: FC<AgentSettingsScreenProps> = () => {
  const [agentId] = useAgentId()
  const { data: agent } = useAgent(agentId)
  const { mutateAsync: deleteAgent, isPending: isDeletingAgent } = useDeleteAgent()
  const { theme } = useAppTheme()

  useLettaHeader(
    {
      title: agent?.name,
      rightIcon: "Trash",
      rightIconColor: "red",
      onRightPress: () => {
        Alert.alert("Delete Agent", "Are you sure you want to delete this agent?", [
          { text: "Cancel", style: "cancel" },
          { text: "Delete", style: "destructive", onPress: () => deleteAgent({ agentId }) },
        ])
      },
      leftIcon: "SquarePen",
      leftIconColor: theme.colors.tint,
      onLeftPress: () => {
        navigate("EditAgent", { agentId })
      },
    },
    [agent, isDeletingAgent],
  )

  return (
    <Screen style={$root} preset="auto">
      <View style={$container}>
        <AgentName />
        <View style={$sectionContainer}>
          <Text preset="heading" text="Description" style={$sectionTitle} />
          <AgentDescription />
        </View>

        <View style={$sectionContainer}>
          <Text preset="heading" text="Memory Blocks" style={$sectionTitle} />
          <AgentBlocks />
        </View>

        <View style={$sectionContainer}>
          <Text preset="heading" text="System Prompt" style={$sectionTitle} />
          <AgentSystemPrompt />
        </View>

        <View style={$sectionContainer}>
          <Text preset="heading" text="Tags" style={$sectionTitle} />
          <AgentTags />
        </View>

        <View style={$sectionContainer}>
          <Text preset="heading" text="Configuration" style={$sectionTitle} />
          <AgentConfig />
        </View>

        <View style={$sectionContainer}>
          <Text preset="heading" text="Environment Variables" style={$sectionTitle} />
          <AgentEnvVars />
        </View>
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

const $sectionTitle: ViewStyle = {
  marginBottom: spacing.xs,
}

const $sectionContainer: ViewStyle = {}
