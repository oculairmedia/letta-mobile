import { useLettaClient } from "@/providers/LettaProvider"
import { Tool } from "@letta-ai/letta-client/resources/tools"
import { useQuery, UseQueryOptions } from "@tanstack/react-query"

export const getAgentToolsQueryKey = (agentId: string) => ["agentTools", agentId]

export function useAgentTools(
  agentId: string,
  queryOptions?: Omit<UseQueryOptions<Tool[]>, "queryKey" | "queryFn">,
) {
  const { lettaClient } = useLettaClient()
  return useQuery<Tool[]>({
    queryKey: getAgentToolsQueryKey(agentId),
    queryFn: async () => {
      const allTools: Tool[] = []
      const page = await lettaClient.agents.tools.list(agentId)
      for await (const tool of page) {
        allTools.push(tool)
      }
      return allTools
    },
    enabled: !!agentId && !!lettaClient,
    ...queryOptions,
  })
}

// Helper to categorize tools into regular vs MCP
export const categorizeTools = (tools: Tool[]) => {
  const categories: { regular: Tool[]; mcp: Tool[] } = {
    regular: [],
    mcp: [],
  }

  for (const tool of tools) {
    if (tool.tool_type === "external_mcp") {
      categories.mcp.push(tool)
    } else {
      categories.regular.push(tool)
    }
  }

  return categories
}
