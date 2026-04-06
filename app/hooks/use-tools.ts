import { useLettaClient } from "@/providers/LettaProvider"
import { Tool } from "@letta-ai/letta-client/resources/tools"
import { useQuery, UseQueryOptions } from "@tanstack/react-query"

export const getToolsQueryKey = () => ["tools"]

export function useTools(queryOptions?: Omit<UseQueryOptions<Tool[]>, "queryKey" | "queryFn">) {
  const { lettaClient } = useLettaClient()
  return useQuery<Tool[]>({
    queryKey: getToolsQueryKey(),
    queryFn: async () => {
      const allTools: Tool[] = []
      const page = await lettaClient.tools.list()
      for await (const tool of page) {
        allTools.push(tool)
      }
      return allTools
    },
    enabled: !!lettaClient,
    ...queryOptions,
  })
}

// Separate tools into regular and MCP
export const categorizeAllTools = (tools: Tool[]) => {
  const regular: Tool[] = []
  const mcp: Tool[] = []

  for (const tool of tools) {
    if (tool.tool_type === "external_mcp") {
      mcp.push(tool)
    } else {
      regular.push(tool)
    }
  }

  return { regular, mcp }
}
