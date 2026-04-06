import { Icon } from "@/components/Icon"
import { Text } from "@/components/Text"
import { categorizeTools, useAgentTools } from "@/hooks/use-agent-tools"
import { useAgentStore } from "@/providers/AgentProvider"
import { normalizeName } from "@/shared/utils/normalizers"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Tool } from "@letta-ai/letta-client/resources/tools"
import { FC, useMemo, useState } from "react"
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native"

const getToolTypeLabel = (toolType: string | undefined): string => {
  if (toolType === "external_mcp") return "MCP"
  if (toolType === "custom") return "Custom"
  return "Built-in"
}

interface ToolItemProps {
  tool: Tool
}

const ToolItem: FC<ToolItemProps> = ({ tool }) => {
  const { themed, theme } = useAppTheme()
  const { colors } = theme

  return (
    <View style={themed($toolItem)}>
      <View style={$toolHeader}>
        <Text size="sm" preset="bold" numberOfLines={1} style={$toolName}>
          {normalizeName(tool.name || "Unnamed Tool")}
        </Text>
        <View style={[$typeBadge, { backgroundColor: colors.palette.overlay20 }]}>
          <Text size="xxs" style={themed($typeText)}>
            {getToolTypeLabel(tool.tool_type)}
          </Text>
        </View>
      </View>
      {tool.description && (
        <Text size="xs" style={themed($toolDescription)} numberOfLines={2}>
          {tool.description}
        </Text>
      )}
    </View>
  )
}

interface CategorySectionProps {
  title: string
  tools: Tool[]
  icon: string
  color: string
}

const CategorySection: FC<CategorySectionProps> = ({ title, tools, icon, color }) => {
  const [expanded, setExpanded] = useState(true)

  if (tools.length === 0) return null

  return (
    <View style={$categorySection}>
      <TouchableOpacity style={$categoryHeader} onPress={() => setExpanded(!expanded)}>
        <View style={$categoryTitleRow}>
          <Icon icon={icon as any} size={16} color={color} />
          <Text preset="bold" size="sm">
            {title}
          </Text>
          <View style={[$countBadge, { backgroundColor: color + "20" }]}>
            <Text size="xxs" style={{ color }}>
              {tools.length}
            </Text>
          </View>
        </View>
        <Icon icon={expanded ? "ChevronUp" : "ChevronDown"} size={16} color={color} />
      </TouchableOpacity>
      {expanded && (
        <View style={$toolsList}>
          {tools.map((tool) => (
            <ToolItem key={tool.id} tool={tool} />
          ))}
        </View>
      )}
    </View>
  )
}

export const AgentToolsButton: FC = () => {
  const { themed, theme } = useAppTheme()
  const { colors } = theme
  const [modalVisible, setModalVisible] = useState(false)

  const agentId = useAgentStore((s) => s.agentId)
  const { data: tools, isLoading } = useAgentTools(agentId)

  const categories = useMemo(() => {
    if (!tools) return null
    return categorizeTools(tools)
  }, [tools])

  const totalTools = tools?.length || 0

  return (
    <>
      <TouchableOpacity
        style={themed($toolsButton)}
        onPress={() => setModalVisible(true)}
        activeOpacity={0.7}
      >
        <Icon icon="Wrench" size={14} color={colors.textDim} />
        <Text size="xs" style={themed($toolsButtonText)}>
          {totalTools} tools
        </Text>
      </TouchableOpacity>

      <Modal
        visible={modalVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setModalVisible(false)}
      >
        <Pressable style={$modalOverlay} onPress={() => setModalVisible(false)}>
          <Pressable style={themed($modalContent)} onPress={(e) => e.stopPropagation()}>
            <View style={$modalHeader}>
              <Text preset="bold">Agent Tools</Text>
              <TouchableOpacity onPress={() => setModalVisible(false)}>
                <Icon icon="X" size={20} color={colors.text} />
              </TouchableOpacity>
            </View>

            {isLoading ? (
              <View style={$loadingContainer}>
                <ActivityIndicator size="small" color={colors.tint} />
              </View>
            ) : (
              <ScrollView style={$scrollContent} showsVerticalScrollIndicator={false}>
                {categories?.regular && categories.regular.length > 0 && (
                  <CategorySection
                    title="Tools"
                    tools={categories.regular}
                    icon="Wrench"
                    color={colors.tint}
                  />
                )}
                {categories?.mcp && categories.mcp.length > 0 && (
                  <CategorySection
                    title="MCP"
                    tools={categories.mcp}
                    icon="Server"
                    color={colors.palette.accent500 || "#10B981"}
                  />
                )}
                {totalTools === 0 && (
                  <View style={$emptyState}>
                    <Icon icon="Wrench" size={32} color={colors.textDim} />
                    <Text style={themed($emptyText)}>No tools attached to this agent</Text>
                  </View>
                )}
              </ScrollView>
            )}
          </Pressable>
        </Pressable>
      </Modal>
    </>
  )
}

const $toolsButton: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xxs,
  paddingHorizontal: spacing.xs,
  paddingVertical: spacing.xxs,
  backgroundColor: colors.palette.overlay20,
  borderRadius: 6,
})

const $toolsButtonText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})

const $modalOverlay: ViewStyle = {
  flex: 1,
  backgroundColor: "rgba(0,0,0,0.5)",
  justifyContent: "center",
  alignItems: "center",
  padding: spacing.lg,
}

const $modalContent: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.background,
  borderRadius: 12,
  width: "100%",
  maxWidth: 400,
  maxHeight: "80%",
})

const $modalHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  padding: spacing.md,
  borderBottomWidth: 1,
  borderBottomColor: "rgba(255,255,255,0.1)",
}

const $scrollContent: ViewStyle = {
  padding: spacing.sm,
}

const $loadingContainer: ViewStyle = {
  padding: spacing.xl,
  alignItems: "center",
}

const $categorySection: ViewStyle = {
  marginBottom: spacing.md,
}

const $categoryHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  paddingVertical: spacing.xs,
}

const $categoryTitleRow: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xs,
}

const $countBadge: ViewStyle = {
  paddingHorizontal: spacing.xxs,
  paddingVertical: 1,
  borderRadius: 4,
}

const $toolsList: ViewStyle = {
  gap: spacing.xs,
  marginTop: spacing.xs,
}

const $toolItem: ThemedStyle<ViewStyle> = ({ colors }) => ({
  padding: spacing.xs,
  backgroundColor: colors.palette.overlay20,
  borderRadius: 6,
})

const $toolHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  gap: spacing.xs,
}

const $toolName: TextStyle = {
  flex: 1,
}

const $typeBadge: ViewStyle = {
  paddingHorizontal: spacing.xxs,
  paddingVertical: 1,
  borderRadius: 4,
}

const $typeText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})

const $toolDescription: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
  marginTop: spacing.xxs,
})

const $emptyState: ViewStyle = {
  alignItems: "center",
  justifyContent: "center",
  padding: spacing.xl,
  gap: spacing.sm,
}

const $emptyText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
  textAlign: "center",
})
