import { useAgent } from "@/hooks/use-agent"
import { useAgentId } from "@/hooks/use-agentId-param"
import { Accordion } from "@/shared/components/animated/Accordion"
import { ToolCard } from "@/shared/components/tools/tool-card"
import { spacing } from "@/theme"
import { normalizeTypeName } from "@/shared/utils/normalizers"
import { FC, useCallback, useMemo, useState } from "react"
import { View, ViewStyle } from "react-native"
import { DetachToolAction } from "../tools/detach-tool-action"
import { AddAgentToolButton } from "./AddAgentToolButton"
interface AgentToolsProps {
  style?: ViewStyle
}

export const AgentTools: FC<AgentToolsProps> = ({ style }) => {
  const [agentId] = useAgentId()
  const { data: agent } = useAgent(agentId)
  const [expandedTypes, setExpandedTypes] = useState<Record<string, boolean>>({})

  const types = useMemo(() => {
    if (!agent) return []
    return [...new Set(agent.tools.map((tool) => tool.toolType))].filter(Boolean)
  }, [agent])

  const filterByType = useCallback(
    (type: string) => agent?.tools.filter((tool) => tool.toolType === type),
    [agent],
  )

  const toggleType = useCallback((type: string) => {
    setExpandedTypes((prev) => ({
      ...prev,
      [type]: !prev[type],
    }))
  }, [])

  return (
    <View style={[$toolsContainer, style]}>
      {types?.map((type) => (
        <Accordion
          key={type}
          isExpanded={expandedTypes[type] ?? false}
          onToggle={() => toggleType(type)}
          text={normalizeTypeName(type)}
          preset="reversed"
        >
          <View style={$toolContainer}>
            {filterByType(type)?.map((tool) => (
              <ToolCard
                key={tool.id}
                tool={tool}
                {...(type === "custom" || type === "external_mcp"
                  ? {
                      RightComponent: <DetachToolAction tool={tool} />,
                    }
                  : undefined)}
              />
            ))}
          </View>
        </Accordion>
      ))}
      <AddAgentToolButton />
    </View>
  )
}

const $toolsContainer: ViewStyle = {
  flexDirection: "column",
  gap: spacing.sm,
}

const $toolContainer: ViewStyle = {
  flexDirection: "column",
  gap: spacing.sm,
  paddingVertical: spacing.sm,
}
