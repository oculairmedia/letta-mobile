import { useLettaClient } from "@/providers/LettaProvider"
import {
  McpServerCreateParams
} from "@letta-ai/letta-client/resources/mcp-servers/mcp-servers"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { useMemo } from "react"

export const getUseMCPListKey = () => ["mcp-list"]
export const getUseMCPToolsKey = () => ["mcp-tools"]
export const getUseMCPToolsByServerKey = (serverId: string) => ["mcp-tools", serverId]

export function useMCPList() {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getUseMCPListKey(),
    queryFn: () => lettaClient.mcpServers.list(),
  })
}

export function useMCPTools() {
  const { lettaClient } = useLettaClient()
  const { data: mcpServers, isLoading: isLoadingServers } = useMCPList()
  const serverList = useMemo(() => mcpServers || [], [mcpServers])

  return useQuery({
    queryKey: getUseMCPToolsKey(),
    queryFn: async () => {
      if (!serverList.length) return []

      const tools = await Promise.all(
        serverList.map(async (server) => {
          try {
            const serverId = server.id
            if (!serverId) return []

            const serverToolsPage = await lettaClient.mcpServers.tools.list(serverId)
            const serverTools = []
            for await (const tool of serverToolsPage) {
              serverTools.push(tool)
            }

            return serverTools.map((tool) => ({
              ...tool,
              serverName: server.server_name,
              serverType: server.mcp_server_type,
            }))
          } catch (error) {
            console.error(
              `Failed to fetch tools for server ${server.server_name} (ID: ${server.id}):`,
              error,
            )
            return []
          }
        }),
      )
      return tools.flat()
    },
    enabled: !isLoadingServers && serverList.length > 0,
  })
}

export function useAddMCPServer() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (payload: McpServerCreateParams) =>
      lettaClient.mcpServers.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getUseMCPListKey() })
      queryClient.invalidateQueries({ queryKey: getUseMCPToolsKey() })
    },
  })
}

export function useDeleteMCPServer() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (serverId: string) => lettaClient.mcpServers.delete(serverId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getUseMCPListKey() })
      queryClient.invalidateQueries({ queryKey: getUseMCPToolsKey() })
    },
  })
}
