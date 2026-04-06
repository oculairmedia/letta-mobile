import { Button, Icon, Screen, Text, TextField } from "@/components"
import { Card } from "@/components/Card"
import { EmptyState } from "@/components/EmptyState"
import { useAgentId } from "@/hooks/use-agentId-param"
import {
  useAttachFolderToAgent,
  useDetachFolderFromAgent,
  useGetAgentFolders,
  useGetFolders,
} from "@/hooks/use-get-folders"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Folder } from "@letta-ai/letta-client/resources/folders/folders"
import { FlashList } from "@shopify/flash-list"
import { FC, useCallback, useMemo, useState } from "react"
import { Modal, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface FoldersModalProps {
  visible: boolean
  onDismiss: () => void
}

const FolderCard = ({
  agentId,
  folder,
  isAttached,
}: {
  agentId: string
  folder: Folder
  isAttached: boolean
}) => {
  const attachFolder = useAttachFolderToAgent()
  const detachFolder = useDetachFolderFromAgent()
  const handleToggleFolder = useCallback(
    (folderId: string, isAttached: boolean) => {
      if (isAttached) {
        detachFolder.mutate({ agentId, folderId })
      } else {
        attachFolder.mutate({ agentId, folderId })
      }
    },
    [agentId, attachFolder, detachFolder],
  )
  const isLoading = useMemo(() => {
    return attachFolder.isPending || detachFolder.isPending
  }, [attachFolder.isPending, detachFolder.isPending])

  return (
    <Card
      heading={folder.name || "Unnamed Folder"}
      content={folder.description || "No description"}
      RightComponent={
        <Button
          text={isLoading ? "" : isAttached ? "Detach" : "Attach"}
          onPress={() => handleToggleFolder(folder.id!, isAttached)}
          preset={isAttached ? "default" : "reversed"}
          loading={isLoading}
        />
      }
    />
  )
}

export const FoldersModal: FC<FoldersModalProps> = ({ visible, onDismiss }) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()
  const [agentId] = useAgentId()
  const [search, setSearch] = useState("")

  const { data: agentFolders, refetch: refetchAgentFolders } = useGetAgentFolders(agentId || "")
  const { data: allFolders, refetch: refetchAllFolders } = useGetFolders()

  const foldersMap = useMemo(() => {
    const map = new Map<string, boolean>()

    allFolders?.forEach((folder) => {
      map.set(folder.id!, agentFolders?.some((f) => f.id === folder.id) ?? false)
    })
    return map
  }, [allFolders, agentFolders])

  const filteredFolders = useMemo(() => {
    if (!allFolders) return []
    const query = search.toLowerCase()
    return allFolders.filter(
      (folder) =>
        folder.name?.toLowerCase().includes(query) ||
        folder.description?.toLowerCase().includes(query),
    )
  }, [foldersMap]) // Re-filter when attachment status could theoretically change context, though mainly search

  const renderItem = useCallback(
    ({ item }: { item: Folder }) => (
      <View key={item.id} style={$folderContainer}>
        <FolderCard
          folder={item}
          isAttached={foldersMap.get(item.id!) ?? false}
          agentId={agentId || ""}
        />
      </View>
    ),
    [foldersMap, agentId],
  )

  const handleRefresh = useCallback(() => {
    refetchAgentFolders()
    refetchAllFolders()
  }, [refetchAgentFolders, refetchAllFolders])

  const keyExtractor = useCallback((item: Folder) => item.id!, [])

  return (
    <Modal
      visible={visible}
      onRequestClose={onDismiss}
      animationType="slide"
      presentationStyle="pageSheet"
    >
      <Screen
        preset="fixed"
        style={themed($modalContainer)}
        contentContainerStyle={[themed($contentContainer), { paddingBottom: bottom }]}
      >
        <View style={$headerContainer}>
          <Text text="Agent Folders" preset="subheading" />
          <Button onPress={handleRefresh} style={$refreshButton}>
            <Icon icon="RotateCcw" size={16} />
          </Button>
        </View>
        <Button onPress={onDismiss} text="Close" />
        <TextField
          placeholder="Search folders"
          value={search}
          onChangeText={setSearch}
          style={$searchInput}
        />
        <View style={$listContainer}>
          <FlashList
            data={filteredFolders}
            estimatedItemSize={117}
            bounces={false}
            contentContainerStyle={themed($flashListContentContainer)}
            renderItem={renderItem}
            keyExtractor={keyExtractor}
            ListEmptyComponent={() => (
              <EmptyState
                heading="No folders found"
                content="Add a folder to your agent"
                button="Refresh"
                buttonOnPress={() => {
                  refetchAllFolders()
                }}
                icon="FileStack"
              />
            )}
          />
        </View>
      </Screen>
    </Modal>
  )
}

const $modalContainer: ViewStyle = {
  flex: 1,
}

const $contentContainer: ViewStyle = {
  padding: spacing.md,
  gap: spacing.md,
  flex: 1,
}

const $headerContainer: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $listContainer: ViewStyle = {
  flex: 1,
  width: "100%",
}

const $flashListContentContainer: ViewStyle = {}

const $folderContainer: ViewStyle = {
  width: "100%",
  marginBottom: spacing.md,
}

const $searchInput: ViewStyle = {}

const $refreshButton: ViewStyle = {
  justifyContent: "center",
  alignItems: "center",
  padding: 0,
  margin: 0,
  minWidth: 0,
}
