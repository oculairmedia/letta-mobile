import { Card, Icon, Screen, Text } from "@/components"
import { Badge } from "@/components/Badge"
import { Button } from "@/components/Button"
import { AddMCPServerModal } from "@/components/custom/modals/add-mcp-server-modal"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useAddMCPServer, useDeleteMCPServer, useMCPList } from "@/hooks/use-mcp"
import { AppStackScreenProps } from "@/navigators"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { McpServerCreateParams, SseMcpServer, StdioMcpServer, StreamableHTTPMcpServer } from "@letta-ai/letta-client/resources/mcp-servers"
import { FC, Fragment, useState } from "react"
import { Alert, FlatList, RefreshControl, TextStyle, View, ViewStyle } from "react-native"

interface MCPServerCardProps {
  server: SseMcpServer | StdioMcpServer | StreamableHTTPMcpServer
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

const MCPServerCard: FC<MCPServerCardProps> = ({ server, onDelete }) => {
  const { themed } = useAppTheme()

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
              <Text style={themed($serverContentTextStyle)}>URL: {server.server_url}</Text>
            ) : (
              <Fragment>
                <Text style={themed($serverContentTextStyle)}>Command: {server.command}</Text>
                <Text style={themed($serverContentTextStyle)}>Args: {server.args.join(" ")}</Text>
              </Fragment>
            )}
          </View>
        }
        RightComponent={<Badge text={server.mcp_server_type!} />}
      />
    </SimpleContextMenu>
  )
}

export const MCPScreen: FC<AppStackScreenProps<"MCP">> = () => {
  useLettaHeader()

  const [isAddModalVisible, setIsAddModalVisible] = useState(false)
  const { data: servers, refetch, isFetching } = useMCPList()
  const addServerMutation = useAddMCPServer()
  const deleteServerMutation = useDeleteMCPServer()

  const {
    theme: { colors },
  } = useAppTheme()

  const handleAddServer = (serverData: McpServerCreateParams) => {
    addServerMutation.mutate(serverData)
    setIsAddModalVisible(false)
  }

  const handleDeleteServer = (serverName: string) => {
    Alert.alert("Delete MCP Server", "Are you sure you want to delete this server?", [
      {
        text: "Cancel",
        style: "cancel",
      },
      {
        text: "Delete",
        style: "destructive",
        onPress: () => {
          deleteServerMutation.mutate(serverName)
        },
      },
    ])
  }

  return (
    <Screen style={$root} preset="fixed" contentContainerStyle={$contentContainer}>
      <View style={$header}>
        <Button
          onPress={() => setIsAddModalVisible(true)}
          text="Add MCP Server"
          loading={addServerMutation.isPending}
          disabled={addServerMutation.isPending}
          LeftAccessory={() => (
            <Icon icon="Plus" size={20} color={colors.elementColors.card.default.content} />
          )}
        />
      </View>

      <FlatList
        data={servers || []}
        bounces={!!servers && servers.length > 0}
        keyExtractor={(item) => item.server_name}
        refreshControl={<RefreshControl refreshing={isFetching} onRefresh={refetch} />}
        refreshing={isFetching}
        ItemSeparatorComponent={() => <View style={{ height: spacing.sm }} />}
        renderItem={({ item }) => (
          <MCPServerCard server={item} onDelete={() => handleDeleteServer(item.server_name)} />
        )}
        ListEmptyComponent={<Text>No MCP Servers</Text>}
        contentContainerStyle={{ padding: spacing.sm }}
      />

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

const $header: ViewStyle = {
  flexDirection: "row",
  justifyContent: "flex-end",
  alignItems: "center",
  padding: spacing.sm,
  gap: spacing.sm,
}

const $serverContentContainer: ViewStyle = {
  flex: 1,
}

const $serverContentTextStyle: ThemedStyle<TextStyle> = () => ({
  opacity: 0.8,
})
