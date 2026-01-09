import { Text } from "@/components/Text"
import { useAgent } from "@/hooks/use-agent"
import { useAgentId } from "@/hooks/use-agentId-param"
import { Accordion } from "@/shared/components/animated/Accordion"
import { FC, useCallback, useState } from "react"
import { View, ViewStyle } from "react-native"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"

interface AgentConfigProps {
  style?: ViewStyle
}

export const AgentConfig: FC<AgentConfigProps> = ({ style }) => {
  const { themed } = useAppTheme()
  const [agentId] = useAgentId()
  const { data: agent } = useAgent(agentId)
  const [expandedConfig, setExpandedConfig] = useState<Record<string, boolean>>({})

  const toggleConfig = useCallback((key: string) => {
    setExpandedConfig((prev) => ({
      ...prev,
      [key]: !prev[key],
    }))
  }, [])

  if (!agent) return null

  return (
    <View style={[$configContainer, style]}>
      <Accordion
        isExpanded={expandedConfig["llm"] ?? false}
        onToggle={() => toggleConfig("llm")}
        text="LLM Configuration"
        preset="reversed"
      >
        <View style={themed($configContent)}>
          <Text text={JSON.stringify(agent.llmConfig, null, 2)} />
        </View>
      </Accordion>

      <Accordion
        isExpanded={expandedConfig["embedding"] ?? false}
        onToggle={() => toggleConfig("embedding")}
        text="Embedding Configuration"
        preset="reversed"
      >
        <View style={themed($configContent)}>
          <Text text={JSON.stringify(agent.embeddingConfig, null, 2)} />
        </View>
      </Accordion>
    </View>
  )
}

const $configContainer: ViewStyle = {
  flexDirection: "column",
  gap: spacing.sm,
}

const $configContent: ThemedStyle<ViewStyle> = ({ colors }) => ({
  padding: spacing.sm,
  backgroundColor: colors.transparent50,
  borderRadius: spacing.xs,
})
