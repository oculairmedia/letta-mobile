import { Card, Icon, Screen, Text } from "@/components"
import { Badge } from "@/components/Badge"
import { AddMCPServerModal } from "@/components/custom/modals/add-mcp-server-modal"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useAddMCPServer, useDeleteMCPServer, useMCPList, useMCPTools } from "@/hooks/use-mcp"
import { useTools } from "@/hooks/use-tools"
import { AppStackScreenProps } from "@/navigators"
import { BareAccordion } from "@/shared/components/animated/BareAccordion"
import { normalizeName } from "@/shared/utils/normalizers"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import {
  McpServerCreateParams,
  SseMcpServer,
  StdioMcpServer,
  StreamableHTTPMcpServer,
} from "@letta-ai/letta-client/resources/mcp-servers"
import { Tool } from "@letta-ai/letta-client/resources/tools"
import { FC, Fragment, useMemo, useState } from "react"
import {
  Alert,
  RefreshControl,
  ScrollView,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native"

type TabType = "tools" | "mcp"

interface MCPTool {
  name: string
  description?: string | null
  serverName: string
}

interface MCPServerCardProps {
  server: SseMcpServer | StdioMcpServer | StreamableHTTPMcpServer
  tools: MCPTool[]
  isLoadingTools: boolean
  onDelete: () => void
}

const isSseServer = (
  server: SseMcpServer | StdioMcpServer | StreamableHTTPMcpServer,
): server is SseMcpServer => {
  return (server as SseMcpServer).mcp_server_type === "sse"
}

const isStreamableHTTPServer = (
  server: SseMcpServer | StdioMcpServer | StreamableHTTPMcpServer,
): server is StreamableHTTPMcpServer => {
  return (server as StreamableHTTPMcpServer).mcp_server_type === "streamable_http"
}

const getToolTypeLabel = (toolType: string | undefined): string => {
  switch (toolType) {
    case "custom":
      return "Custom"
    case "letta_core":
    case "letta_memory_core":
    case "letta_multi_agent_core":
    case "letta_sleeptime_core":
    case "letta_voice_sleeptime_core":
    case "letta_files_core":
      return "Core"
    case "letta_builtin":
      return "Built-in"
    case "external_langchain":
      return "LangChain"
    case "external_composio":
      return "Composio"
    default:
      return "Tool"
  }
}

// Regular tool card component
const ToolCard: FC<{ tool: Tool }> = ({ tool }) => {
  const { themed } = useAppTheme()

  return (
    <Card
      heading={normalizeName(tool.name || "Unnamed Tool")}
      ContentComponent={
        tool.description ? (
          <Text style={themed($toolDescriptionText)} size="xs" numberOfLines={2}>
            {tool.description}
          </Text>
        ) : undefined
      }
      RightComponent={<Badge text={getToolTypeLabel(tool.tool_type)} />}
    />
  )
}

// MCP Server card component
const MCPServerCard: FC<MCPServerCardProps> = ({ server, tools, isLoadingTools, onDelete }) => {
  const { themed } = useAppTheme()
  const [isExpanded, setIsExpanded] = useState(false)

  const toolCount = tools.length
  const toolCountText = isLoadingTools
    ? "Loading tools..."
    : `${toolCount} tool${toolCount === 1 ? "" : "s"}`

  return (
    <SimpleContextMenu
      actions={[
        {
          key: "delete",
          title: "Delete",
          iosIconName: { name: "trash", weight: "bold" },
          androidIconName: "ic_menu_delete",
          onPress: onDelete,
        },
      ]}
    >
      <Card
        heading={server.server_name || "Unnamed MCP Server"}
        ContentComponent={
          <View style={$serverContentContainer}>
            {isSseServer(server) || isStreamableHTTPServer(server) ? (
              <Text style={themed($serverContentTextStyle)} size="xs">
                URL: {server.server_url}
              </Text>
            ) : (
              <Fragment>
                <Text style={themed($serverContentTextStyle)} size="xs">
                  Command: {server.command}
                </Text>
                <Text style={themed($serverContentTextStyle)} size="xs">
                  Args: {server.args.join(" ")}
                </Text>
              </Fragment>
            )}

            <View style={$toolCountContainer}>
              <Badge text={toolCountText} />
            </View>

            {toolCount > 0 && (
              <BareAccordion
                isExpanded={isExpanded}
                onToggle={() => setIsExpanded(!isExpanded)}
                style={$toolsAccordion}
                triggerNode={({ animatedChevron }) => (
                  <View style={$toolsHeader}>
                    <Text preset="bold" size="xs">
                      {isExpanded ? "Hide Tools" : "Show Tools"}
                    </Text>
                    {animatedChevron}
                  </View>
                )}
              >
                <View style={$toolsList}>
                  {tools.map((tool, index) => (
                    <View key={tool.name + index} style={themed($toolItem)}>
                      <Text preset="bold" size="xs">
                        {tool.name}
                      </Text>
                      {tool.description && (
                        <Text style={themed($toolDescription)} size="xxs" numberOfLines={2}>
                          {tool.description}
                        </Text>
                      )}
                    </View>
                  ))}
                </View>
              </BareAccordion>
            )}
          </View>
        }
        RightComponent={<Badge text={server.mcp_server_type!} />}
      />
    </SimpleContextMenu>
  )
}

export const MCPScreen: FC<AppStackScreenProps<"Tools">> = () => {
  useLettaHeader()

  const [activeTab, setActiveTab] = useState<TabType>("tools")
  const [isAddModalVisible, setIsAddModalVisible] = useState(false)

  const {
    theme: { colors },
    themed,
  } = useAppTheme()

  // Regular tools (non-MCP)
  const { data: allServerTools, refetch: refetchTools, isFetching: isFetchingTools } = useTools()
  const regularTools = useMemo(() => {
    if (!allServerTools) return []
    return allServerTools.filter((t) => t.tool_type !== "external_mcp")
  }, [allServerTools])

  // MCP servers and tools
  const { data: servers, refetch: refetchServers, isFetching: isFetchingServers } = useMCPList()
  const { data: mcpTools, isLoading: isLoadingMCPTools, refetch: refetchMCPTools } = useMCPTools()
  const addServerMutation = useAddMCPServer()
  const deleteServerMutation = useDeleteMCPServer()

  // Group MCP tools by server name
  const toolsByServer = useMemo(() => {
    const grouped: Record<string, MCPTool[]> = {}
    if (!mcpTools) return grouped

    for (const tool of mcpTools) {
      const serverName = tool.serverName || "Unknown"
      if (!grouped[serverName]) {
        grouped[serverName] = []
      }
      grouped[serverName].push({
        name: tool.name || "Unnamed Tool",
        description: tool.description,
        serverName: tool.serverName,
      })
    }
    return grouped
  }, [mcpTools])

  const handleAddServer = (serverData: McpServerCreateParams) => {
    addServerMutation.mutate(serverData)
    setIsAddModalVisible(false)
  }

  const handleDeleteServer = (serverName: string) => {
    Alert.alert("Delete MCP Server", "Are you sure you want to delete this server?", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete",
        style: "destructive",
        onPress: () => deleteServerMutation.mutate(serverName),
      },
    ])
  }

  const handleRefresh = () => {
    refetchTools()
    refetchServers()
    refetchMCPTools()
  }

  const isFetching = isFetchingTools || isFetchingServers

  return (
    <Screen style={$root} preset="fixed" contentContainerStyle={$contentContainer}>
      {/* Tab bar */}
      <View style={themed($tabBar)}>
        <TouchableOpacity
          style={[themed($tab), activeTab === "tools" && themed($tabActive)]}
          onPress={() => setActiveTab("tools")}
        >
          <Icon
            icon="Wrench"
            size={16}
            color={activeTab === "tools" ? colors.tint : colors.textDim}
          />
          <Text
            size="sm"
            preset={activeTab === "tools" ? "bold" : "default"}
            style={activeTab === "tools" ? { color: colors.tint } : themed($tabText)}
          >
            Tools ({regularTools.length})
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[themed($tab), activeTab === "mcp" && themed($tabActive)]}
          onPress={() => setActiveTab("mcp")}
        >
          <Icon
            icon="Server"
            size={16}
            color={activeTab === "mcp" ? colors.tint : colors.textDim}
          />
          <Text
            size="sm"
            preset={activeTab === "mcp" ? "bold" : "default"}
            style={activeTab === "mcp" ? { color: colors.tint } : themed($tabText)}
          >
            MCP ({servers?.length || 0})
          </Text>
        </TouchableOpacity>
      </View>

      {/* Tools tab content */}
      {activeTab === "tools" && (
        <ScrollView
          style={$scrollView}
          contentContainerStyle={$scrollContent}
          refreshControl={<RefreshControl refreshing={isFetching} onRefresh={handleRefresh} />}
        >
          {regularTools.length === 0 ? (
            <View style={$emptyState}>
              <Icon icon="Wrench" size={48} color={colors.textDim} />
              <Text style={themed($emptyText)}>No tools available</Text>
            </View>
          ) : (
            <View style={$listContainer}>
              {regularTools.map((tool) => (
                <ToolCard key={tool.id} tool={tool} />
              ))}
            </View>
          )}
        </ScrollView>
      )}

      {/* MCP tab content */}
      {activeTab === "mcp" && (
        <>
          <ScrollView
            style={$scrollView}
            contentContainerStyle={$scrollContent}
            refreshControl={<RefreshControl refreshing={isFetching} onRefresh={handleRefresh} />}
          >
            {!servers || servers.length === 0 ? (
              <View style={$emptyState}>
                <Icon icon="Server" size={48} color={colors.textDim} />
                <Text style={themed($emptyText)}>No MCP servers configured</Text>
              </View>
            ) : (
              <View style={$listContainer}>
                {servers.map((server) => (
                  <MCPServerCard
                    key={server.server_name}
                    server={server}
                    tools={toolsByServer[server.server_name] || []}
                    isLoadingTools={isLoadingMCPTools}
                    onDelete={() => handleDeleteServer(server.server_name)}
                  />
                ))}
              </View>
            )}
          </ScrollView>

          <TouchableOpacity
            style={themed($fab)}
            onPress={() => setIsAddModalVisible(true)}
            disabled={addServerMutation.isPending}
            activeOpacity={0.8}
          >
            <Icon icon="Plus" size={24} color="#fff" />
          </TouchableOpacity>
        </>
      )}

      <AddMCPServerModal
        visible={isAddModalVisible}
        onDismiss={() => setIsAddModalVisible(false)}
        onSubmit={handleAddServer}
        isPending={addServerMutation.isPending}
      />
    </Screen>
  )
}

const $root: ViewStyle = {
  flex: 1,
}

const $contentContainer: ViewStyle = {
  flex: 1,
  paddingBottom: spacing.lg,
}

const $tabBar: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  borderBottomWidth: 1,
  borderBottomColor: colors.palette.overlay20,
})

const $tab: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "center",
  gap: spacing.xs,
  paddingVertical: spacing.sm,
})

const $tabActive: ThemedStyle<ViewStyle> = ({ colors }) => ({
  borderBottomWidth: 2,
  borderBottomColor: colors.tint,
})

const $tabText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})

const $fab: ThemedStyle<ViewStyle> = ({ colors }) => ({
  position: "absolute",
  bottom: spacing.lg,
  right: spacing.md,
  width: 56,
  height: 56,
  borderRadius: 28,
  backgroundColor: colors.tint,
  alignItems: "center",
  justifyContent: "center",
  elevation: 4,
  shadowColor: "#000",
  shadowOffset: { width: 0, height: 2 },
  shadowOpacity: 0.25,
  shadowRadius: 4,
})

const $scrollView: ViewStyle = {
  flex: 1,
}

const $scrollContent: ViewStyle = {
  padding: spacing.sm,
}

const $listContainer: ViewStyle = {
  gap: spacing.sm,
}

const $emptyState: ViewStyle = {
  alignItems: "center",
  justifyContent: "center",
  paddingVertical: spacing.xxl,
  gap: spacing.sm,
}

const $emptyText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})

const $toolDescriptionText: ThemedStyle<TextStyle> = () => ({
  opacity: 0.8,
})

const $serverContentContainer: ViewStyle = {
  flex: 1,
  gap: spacing.xs,
}

const $serverContentTextStyle: ThemedStyle<TextStyle> = () => ({
  opacity: 0.8,
})

const $toolCountContainer: ViewStyle = {
  flexDirection: "row",
  marginTop: spacing.xs,
}

const $toolsAccordion: ViewStyle = {
  marginTop: spacing.xs,
}

const $toolsHeader: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xs,
  paddingVertical: spacing.xs,
}

const $toolsList: ViewStyle = {
  gap: spacing.xs,
  paddingTop: spacing.xs,
}

const $toolItem: ThemedStyle<ViewStyle> = ({ colors }) => ({
  paddingVertical: spacing.xxs,
  paddingHorizontal: spacing.xs,
  backgroundColor: colors.palette.overlay20,
  borderRadius: spacing.xxs,
})

const $toolDescription: ThemedStyle<TextStyle> = () => ({
  opacity: 0.7,
})
