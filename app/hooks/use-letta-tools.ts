import { useLettaClient } from "@/providers/LettaProvider"
import { AgentState, AppModel, Tool } from "@letta-ai/letta-client/api"
import { useMutation, UseMutationOptions, useQuery, useQueryClient } from "@tanstack/react-query"
import { getUseAgentStateKey } from "./use-agent"
import { getAgentsQueryKey } from "./use-agents"
export const getLettaToolsQueryKey = () => ["letta-tools"]

const filterTools = (tools: Tool[]) => {
  return tools.filter((tool) => tool.toolType === "custom")
}

export const useLettaTools = () => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getLettaToolsQueryKey(),
    queryFn: () => lettaClient.tools.list(),
    select: filterTools,
    enabled: !!lettaClient,
  })
}

export const getComposioAppsQueryKey = () => ["composio-apps"]

export const useLettaComposioApps = () => {
  const { lettaClient } = useLettaClient()
  return useQuery<AppModel[], Error>({
    queryKey: getComposioAppsQueryKey(),
    queryFn: () => lettaClient.tools.listComposioApps(),
    enabled: !!lettaClient,
  })
}

export const getComposioToolsQueryKey = () => ["composio-tools"]

export const useLettaComposioTools = () => {
  const { lettaClient } = useLettaClient()
  const { data: composioApps } = useLettaComposioApps()
  return useQuery({
    queryKey: getComposioToolsQueryKey(),
    queryFn: async () => {
      if (!composioApps) return []
      const actions = await Promise.all(
        composioApps.map((app) => lettaClient.tools.listComposioActionsByApp(app.name)),
      )

      return actions
    },
    enabled: !!lettaClient && !!composioApps?.length,
  })
}

export function useAttachToolToAgent({
  onSuccess,
  ...mutationOptions
}: UseMutationOptions<AgentState, Error, { agentId: string; toolId: string }> = {}) {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ agentId, toolId }: { agentId: string; toolId: string }) =>
      lettaClient.agents.tools.attach(agentId, toolId),
    onSuccess: (...args) => {
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(args[1].agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      onSuccess?.(...args)
    },
    ...mutationOptions,
  })
}

export function useDetachToolFromAgent({
  onSuccess,
  ...mutationOptions
}: UseMutationOptions<AgentState, Error, { agentId: string; toolId: string }> = {}) {
  const { lettaClient } = useLettaClient()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ agentId, toolId }: { agentId: string; toolId: string }) =>
      lettaClient.agents.tools.detach(agentId, toolId),
    onSuccess: (...args) => {
      queryClient.invalidateQueries({ queryKey: getUseAgentStateKey(args[1].agentId) })
      queryClient.invalidateQueries({ queryKey: getAgentsQueryKey() })
      onSuccess?.(...args)
    },
    ...mutationOptions,
  })
}
