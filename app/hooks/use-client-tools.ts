import { useLettaClient } from "@/providers/LettaProvider"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { clientToolDefinitions } from "../utils/client-tools/registry"
import { getAgentsQueryKey } from "./use-agents"
import { getLettaToolsQueryKey } from "./use-letta-tools"
import { getUseAgentStateKey } from "./use-agent"

export function useSyncClientTools() {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: async ({ agentId }: { agentId: string }) => {
      for (const def of clientToolDefinitions) {
        let tool
        try {
          tool = await lettaClient.tools.upsert({
            source_code: def.sourceCode,
            source_type: "python",
            default_requires_approval: true,
          })
        } catch (e: any) {
          console.log(e)
        }

        if (!tool) {
          console.log(`[SyncClientTools] Could not find tool ${def.name} after creation/fetch.`)
          continue
        }

        try {
          await lettaClient.agents.tools.attach(tool.id, { agent_id: agentId })
        } catch (e: any) {
          console.log(
            `[SyncClientTools] Tool ${def.name} might already be attached or failed to attach:`,
            e.message,
          )
        }
      }
      return true
    },
    onSuccess: (_, { agentId }) => {
      queryClient.invalidateQueries({ queryKey: getLettaToolsQueryKey() })
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
    },
  })
}
