import { Text } from "@/components/Text"
import { useAgent } from "@/hooks/use-agent"
import { useAgentId } from "@/hooks/use-agentId-param"
import { Accordion } from "@/shared/components/animated/Accordion"
import { AgentEnvironmentVariable } from "@letta-ai/letta-client/resources/agents"
import { FC, useState } from "react"
import { TextStyle, View, ViewStyle } from "react-native"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"

interface AgentEnvVarsProps {
  style?: ViewStyle
}

export const AgentEnvVars: FC<AgentEnvVarsProps> = ({ style }) => {
  const { themed } = useAppTheme()
  const [agentId] = useAgentId()
  const { data: agent } = useAgent(agentId)
  const [isExpanded, setIsExpanded] = useState(false)

  if (!agent?.secrets?.length) {
    return (
      <Text
        preset="formHelper"
        text="No environment variables configured"
        style={themed($emptyText)}
      />
    )
  }

  return (
    <Accordion
      isExpanded={isExpanded}
      onToggle={() => setIsExpanded(!isExpanded)}
      text="View Environment Variables"
      preset="reversed"
      style={style}
    >
      <View style={themed($contentContainer)}>
        {agent.secrets.map((envVar: AgentEnvironmentVariable) => (
          <View key={envVar.key} style={themed($envVarContainer)}>
            <Text preset="formLabel" text={envVar.key} />
            <Text preset="formHelper" text={envVar.value} />
          </View>
        ))}
      </View>
    </Accordion>
  )
}

const $contentContainer: ThemedStyle<ViewStyle> = ({ colors }) => ({
  padding: spacing.sm,
  backgroundColor: colors.transparent50,
  borderRadius: spacing.xs,
})

const $envVarContainer: ThemedStyle<ViewStyle> = ({ colors }) => ({
  padding: spacing.sm,
  backgroundColor: colors.transparent50,
  borderRadius: spacing.xs,
  marginBottom: spacing.xs,
})

const $emptyText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})
