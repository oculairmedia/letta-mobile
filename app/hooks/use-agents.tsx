import { useLettaClient } from "@/providers/LettaProvider"
import { Letta } from "@letta-ai/letta-client"
import { useQuery, UseQueryOptions } from "@tanstack/react-query"

export const AgentsQueryKey = ["agents"]
export const getAgentsQueryKey = () => AgentsQueryKey

const sortByLastCreated = (agents: Letta.AgentState[]) => {
  return agents.sort((a, b) => {
    const aCreatedAt = a.createdAt ? new Date(a.createdAt).getTime() : 0
    const bCreatedAt = b.createdAt ? new Date(b.createdAt).getTime() : 0
    return bCreatedAt - aCreatedAt
  })
}

export function useAgents(queryOptions?: UseQueryOptions<Letta.AgentState[]>) {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getAgentsQueryKey(),
    queryFn: () => lettaClient.agents.list(),
    select: sortByLastCreated,
    enabled: !!lettaClient,
    ...queryOptions,
  })
}
