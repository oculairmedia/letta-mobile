import { useLettaClient } from "@/providers/LettaProvider"
import { SourceCreate } from "@letta-ai/letta-client/api"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
const getSourcesQueryKey = () => ["sources"]

export const useGetSources = () => {
  const { lettaClient } = useLettaClient()

  return useQuery({
    queryKey: getSourcesQueryKey(),
    queryFn: () => lettaClient.sources.list(),
  })
}

const getSourceQueryKey = (sourceId: string) => ["source", sourceId]

export const useGetSource = (sourceId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getSourceQueryKey(sourceId),
    queryFn: () => lettaClient.sources.retrieve(sourceId),
  })
}

export const useAddSource = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()

  return useMutation({
    mutationFn: (source: SourceCreate) => lettaClient.sources.create(source),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getSourcesQueryKey() })
    },
  })
}

export const useDeleteSource = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: (sourceId: string) => lettaClient.sources.delete(sourceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getSourcesQueryKey() })
    },
  })
}

export const useAddFileToSource = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()

  return useMutation({
    mutationFn: ({ sourceId, file }: { sourceId: string; file: File }) =>
      lettaClient.sources.files.upload(file, sourceId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getSourcesQueryKey() })
      queryClient.invalidateQueries({ queryKey: getSourceFilesQueryKey(variables.sourceId) })
    },
  })
}

export const useDeleteFileFromSource = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ sourceId, fileId }: { sourceId: string; fileId: string }) =>
      lettaClient.sources.files.delete(sourceId, fileId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getSourcesQueryKey() })
      queryClient.invalidateQueries({ queryKey: getSourceFilesQueryKey(variables.sourceId) })
    },
  })
}

export const getSourceFilesQueryKey = (sourceId: string) => ["source", sourceId, "files"]

export const useGetSourceFiles = (sourceId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getSourceFilesQueryKey(sourceId),
    queryFn: () => lettaClient.sources.files.list(sourceId),
  })
}

export const getAgentSourcesQueryKey = (agentId: string) => ["agent", agentId, "sources"]

export const useGetAgentSources = (agentId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getAgentSourcesQueryKey(agentId),
    queryFn: () => lettaClient.agents.sources.list(agentId),
  })
}

export const useAttachSourceToAgent = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ agentId, sourceId }: { agentId: string; sourceId: string }) =>
      lettaClient.agents.sources.attach(agentId, sourceId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentSourcesQueryKey(variables.agentId) })
    },
  })
}

export const useDetachSourceFromAgent = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ agentId, sourceId }: { agentId: string; sourceId: string }) =>
      lettaClient.agents.sources.detach(agentId, sourceId),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentSourcesQueryKey(variables.agentId) })
    },
  })
}
