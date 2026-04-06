import { Button, Screen, Text, TextField } from "@/components"
import { EmptyState } from "@/components/EmptyState"
import { CustomToolCard } from "@/components/custom/tool-card"
import { useAgent } from "@/hooks/use-agent"
import { useAgentId } from "@/hooks/use-agentId-param"
import { useMCPTools } from "@/hooks/use-mcp"
import { BareAccordion } from "@/shared/components/animated/BareAccordion"
import { AttachMCPToolAction } from "@/shared/components/tools/attach-mcp-tool-action"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FC, useMemo, useState } from "react"
import { Modal, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface MCPToolsModalProps {
  visible: boolean
  onDismiss: () => void
}

interface MCPTool {
  name: string
  description?: string
  serverName: string
  serverType?: "stdio" | "sse" | "streamable_http"
  inputSchema: Record<string, unknown>
}

interface ToolWithAttachmentState extends MCPTool {
  isAttached: boolean
}

interface GroupedTools {
  [serverName: string]: ToolWithAttachmentState[]
}

interface ServerAccordionProps {
  serverName: string
  tools: ToolWithAttachmentState[]
  isExpanded: boolean
  onToggle: () => void
  onToolAttached: () => void
}

const ServerAccordion: FC<ServerAccordionProps> = ({
  serverName,
  tools,
  isExpanded,
  onToggle,
  onToolAttached,
}) => {
  const { themed } = useAppTheme()

  return (
    <BareAccordion
      isExpanded={isExpanded}
      onToggle={onToggle}
      style={$accordion}
      wrapperStyleOverride={$accordionWrapper}
      triggerNode={({ animatedChevron }) => (
        <View style={themed($serverHeader)}>
          <View style={$serverInfo}>
            <Text text={serverName} preset="subheading" />
            <Text
              text={`${tools.length} tool${tools.length === 1 ? "" : "s"}`}
              preset="formHelper"
            />
          </View>
          {animatedChevron}
        </View>
      )}
    >
      <View style={$toolsContainer}>
        {tools.map((tool) => (
          <View key={tool.name + tool.serverName}>
            <CustomToolCard
              tool={tool}
              RightComponent={
                tool.isAttached ? (
                  <View style={$attachedBadge}>
                    <Text text="Attached" preset="formHelper" />
                  </View>
                ) : (
                  <AttachMCPToolAction tool={tool} onSuccess={onToolAttached} />
                )
              }
            />
            <View style={$separator} />
          </View>
        ))}
      </View>
    </BareAccordion>
  )
}

export const MCPToolsModal: FC<MCPToolsModalProps> = ({ visible, onDismiss }) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()
  const { data: mcpToolsData } = useMCPTools()
  const mcpTools = mcpToolsData?.tools || []
  const [search, setSearch] = useState("")
  const [expandedServers, setExpandedServers] = useState<Record<string, boolean>>({})
  const [agentId] = useAgentId()
  const { data: agent } = useAgent(agentId || "")

  const toolAttachmentMap = useMemo(() => {
    const map = new Map<string, boolean>()
    if (!agent?.tools) return map

    for (const tool of agent.tools) {
      const mcpTag = tool.tags?.find((tag) => tag.startsWith("mcp:") && tag.includes(":"))
      if (!mcpTag) continue
      const serverName = mcpTag.split(":").pop()
      if (serverName) {
        map.set(tool.name + ":" + serverName, true)
      }
    }

    return map
  }, [agent?.tools])

  const filteredAndGroupedTools = useMemo(() => {
    if (!mcpTools.length) return {}

    const query = search.toLowerCase()
    const filtered = mcpTools.filter(
      (tool) =>
        tool.name?.toLowerCase().includes(query) || tool.description?.toLowerCase().includes(query),
    )

    return filtered.reduce<GroupedTools>((grouped, tool) => {
      const serverName = tool.serverName || "Unnamed Server"
      if (!grouped[serverName]) {
        grouped[serverName] = []
      }
      grouped[serverName].push({
        ...tool,
        name: tool.name || "",
        serverName: tool.serverName || "",
        inputSchema: tool.args_json_schema || {},
        description: tool.description || undefined,
        isAttached: toolAttachmentMap.get(`${tool.name}:${tool.serverName}`) || false,
      })
      return grouped
    }, {})
  }, [mcpTools, search, toolAttachmentMap])

  const handleToolAttached = () => setSearch("")

  return (
    <Modal
      visible={visible}
      onRequestClose={onDismiss}
      animationType="slide"
      presentationStyle="pageSheet"
    >
      <Screen
        preset="scroll"
        style={themed($modalContainer)}
        contentContainerStyle={[themed($contentContainer), { paddingBottom: bottom }]}
      >
        <View style={$headerContainer}>
          <Text text="MCP Tools" preset="subheading" />
          <Button onPress={onDismiss} text="Close" />
        </View>
        <TextField placeholder="Search" value={search} onChangeText={setSearch} />
        <View style={$listContainer}>
          {Object.keys(filteredAndGroupedTools).length > 0 ? (
            Object.entries(filteredAndGroupedTools).map(([serverName, tools]) => (
              <ServerAccordion
                key={serverName}
                serverName={serverName}
                tools={tools}
                isExpanded={expandedServers[serverName] || false}
                onToggle={() =>
                  setExpandedServers((prev) => ({ ...prev, [serverName]: !prev[serverName] }))
                }
                onToolAttached={handleToolAttached}
              />
            ))
          ) : (
            <EmptyState
              heading="No MCP tools found"
              content="Please add a tool to your agent"
              icon="Blocks"
            />
          )}
        </View>
      </Screen>
    </Modal>
  )
}

const $modalContainer: ViewStyle = {
  flex: 1,
}

const $contentContainer: ViewStyle = {
  padding: spacing.md,
  gap: spacing.md,
}

const $headerContainer: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $listContainer: ViewStyle = {
  width: "100%",
}

const $accordion: ViewStyle = {
  marginBottom: spacing.sm,
}

const $accordionWrapper: ViewStyle = {
  padding: 0,
}

const $serverHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  padding: spacing.sm,
  borderWidth: 1,
  borderColor: "transparent",
}

const $serverInfo: ViewStyle = {
  flex: 1,
}

const $toolsContainer: ViewStyle = {
  padding: spacing.sm,
}

const $separator: ViewStyle = {
  height: spacing.md,
}

const $attachedBadge: ViewStyle = {
  padding: spacing.xs,
  borderRadius: spacing.xs,
  backgroundColor: "rgba(0, 0, 0, 0.1)",
}
