import { useLettaClient } from "@/providers/LettaProvider"
import { FolderCreateParams } from "@letta-ai/letta-client/resources/folders/folders"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Uploadable } from "@letta-ai/letta-client/core/uploads"

const getFoldersQueryKey = () => ["folders"]
const getFolderQueryKey = (folderId: string) => ["folder", folderId]
const getFolderFilesQueryKey = (folderId: string) => ["folder", folderId, "files"]
export const getAgentFoldersQueryKey = (agentId: string) => ["agent", agentId, "folders"]

export const useGetFolders = () => {
  const { lettaClient } = useLettaClient()

  return useQuery({
    queryKey: getFoldersQueryKey(),
    queryFn: () => lettaClient.folders.list().then((page) => page.getPaginatedItems()),
  })
}

export const useGetFolder = (folderId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getFolderQueryKey(folderId),
    queryFn: () => lettaClient.folders.retrieve(folderId),
  })
}

export const useCreateFolder = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()

  return useMutation({
    mutationFn: (folder: FolderCreateParams) => lettaClient.folders.create(folder),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getFoldersQueryKey() })
      // agent folders might change if we auto-attach, but create usually doesn't attach
    },
  })
}

export const useDeleteFolder = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: (folderId: string) => lettaClient.folders.delete(folderId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: getFoldersQueryKey() })
    },
  })
}

export const useAddFileToFolder = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()

  return useMutation({
    mutationFn: ({ folderId, file }: { folderId: string; file: Uploadable }) =>
      // @ts-ignore - SDK types might mismatch slightly on Uploadable vs internal, checking strictly later if specific error persists
      lettaClient.folders.files.upload(folderId, { file }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getFoldersQueryKey() })
      queryClient.invalidateQueries({ queryKey: getFolderFilesQueryKey(variables.folderId) })
    },
  })
}

export const useDeleteFileFromFolder = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ folderId, fileId }: { folderId: string; fileId: string }) =>
      lettaClient.folders.files.delete(fileId, { folder_id: folderId }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getFoldersQueryKey() })
      queryClient.invalidateQueries({ queryKey: getFolderFilesQueryKey(variables.folderId) })
    },
  })
}

export const useGetFolderFiles = (folderId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getFolderFilesQueryKey(folderId),
    queryFn: () =>
      lettaClient.folders.files.list(folderId).then((page) => page.getPaginatedItems()),
  })
}

export const useGetAgentFolders = (agentId: string) => {
  const { lettaClient } = useLettaClient()
  return useQuery({
    queryKey: getAgentFoldersQueryKey(agentId),
    queryFn: () =>
      lettaClient.agents.folders.list(agentId).then((page) => page.getPaginatedItems()),
  })
}

export const useAttachFolderToAgent = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ agentId, folderId }: { agentId: string; folderId: string }) =>
      lettaClient.agents.folders.attach(folderId, { agent_id: agentId }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentFoldersQueryKey(variables.agentId) })
    },
  })
}

export const useDetachFolderFromAgent = () => {
  const queryClient = useQueryClient()
  const { lettaClient } = useLettaClient()
  return useMutation({
    mutationFn: ({ agentId, folderId }: { agentId: string; folderId: string }) =>
      lettaClient.agents.folders.detach(folderId, { agent_id: agentId }),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: getAgentFoldersQueryKey(variables.agentId) })
    },
  })
}
