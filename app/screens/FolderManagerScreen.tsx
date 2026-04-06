import { Button, Card, Icon, Screen, Text, TextField } from "@/components"
import { useAnimatedLoader } from "@/components/custom/animated/loader"
import { EmptyState } from "@/components/EmptyState"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useAgentId } from "@/hooks/use-agentId-param"
import {
  useAddFileToFolder,
  useAttachFolderToAgent,
  useDeleteFileFromFolder,
  useDeleteFolder,
  useDetachFolderFromAgent,
  useGetAgentFolders,
  useGetFolderFiles,
  useGetFolders,
} from "@/hooks/use-get-folders"
import { BareAccordion } from "@/shared/components/animated/BareAccordion"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FileListResponse } from "@letta-ai/letta-client/resources/folders/files"
import { Folder } from "@letta-ai/letta-client/resources/folders/folders"
import * as DocumentPicker from "expo-document-picker"
import { FC, useEffect, useMemo, useState } from "react"
import {
  Alert,
  GestureResponderEvent,
  Platform,
  ScrollView,
  TextStyle,
  View,
  ViewStyle,
} from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface FileCardProps {
  item: FileListResponse
  selectedFolderId: string | null
}

const FileCard: FC<FileCardProps> = ({ item, selectedFolderId }) => {
  const { themed } = useAppTheme()
  const deleteFile = useDeleteFileFromFolder()

  const handleDelete = () => {
    Alert.alert("Delete File", "Are you sure you want to delete this file?", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete",
        style: "destructive",
        onPress: () => {
          if (selectedFolderId) {
            deleteFile.mutate({ folderId: selectedFolderId, fileId: item.id! })
          }
        },
      },
    ])
  }

  return (
    <SimpleContextMenu
      actions={[
        {
          key: "delete",
          title: "Delete",
          iosIconName: { name: "trash", weight: "bold" },
          androidIconName: "ic_menu_delete",
          onPress: handleDelete,
        },
      ]}
    >
      <Card
        key={item.id}
        HeadingTextProps={{ numberOfLines: 1, ellipsizeMode: "middle" }}
        ContentComponent={
          <View style={$fileContentContainer}>
            <Text
              text={item.file_name || "Unnamed File"}
              numberOfLines={1}
              ellipsizeMode="middle"
            />
            <View style={$fileMetadataContainer}>
              <View style={$fileMetadataRow}>
                <Text size="xxs" text="Type:" style={$metadataLabel} />
                <Text size="xxs" text={item.file_type || "Unknown"} />
              </View>
              <View style={$fileMetadataRow}>
                <Text size="xxs" text="Size:" style={$metadataLabel} />
                <Text size="xxs" text={formatFileSize(item.file_size || 0)} />
              </View>
              <View style={$fileMetadataRow}>
                <Text size="xxs" text="Modified:" style={$metadataLabel} />
                <Text size="xxs" text={formatDate(item.file_last_modified_date || "")} />
              </View>
            </View>
          </View>
        }
        style={themed($fileCard)}
      />
    </SimpleContextMenu>
  )
}

interface FolderAccordionProps {
  item: Folder
  selectedFolderId: string | null
  onFolderPress: (folderId: string) => void
  files: FileListResponse[] | undefined
}

const ALLOWED_FILE_TYPES = [
  "application/pdf",
  "text/plain",
  "text/markdown",
  "text/csv",
  "application/json",
  "application/xml",
  "text/html",
  "text/xml",
  "application/x-yaml",
  "text/yaml",
  "application/yaml",
]

const RESERVED_FILENAMES = ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "LPT1", "LPT2"]

const FolderAccordion: FC<FolderAccordionProps> = ({
  item,
  selectedFolderId,
  onFolderPress,
  files,
}) => {
  const { themed } = useAppTheme()
  const [agentId] = useAgentId()
  const { data: agentFolders } = useGetAgentFolders(agentId || "")
  const attachFolder = useAttachFolderToAgent()
  const detachFolder = useDetachFolderFromAgent()
  const deleteFolder = useDeleteFolder()
  const addFile = useAddFileToFolder()

  const isAttached = agentFolders?.some((folder) => folder.id === item.id) ?? false
  const isLoading = attachFolder.isPending || detachFolder.isPending || addFile.isPending

  const handleToggleFolder = () => {
    if (isAttached) {
      detachFolder.mutate({ agentId: agentId || "", folderId: item.id! })
    } else {
      attachFolder.mutate({ agentId: agentId || "", folderId: item.id! })
    }
  }

  const handleDelete = () => {
    Alert.alert("Delete Folder", "Are you sure you want to delete this folder?", [
      { text: "Cancel", style: "cancel" },
      {
        text: "Delete",
        style: "destructive",
        onPress: () => {
          deleteFolder.mutate(item.id!)
        },
      },
    ])
  }

  const handleUploadFile = async () => {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ALLOWED_FILE_TYPES,
        copyToCacheDirectory: true,
        multiple: false,
      })

      if (result.canceled) {
        return
      }

      if (result.assets.length === 0) {
        return
      }

      const file = result.assets[0]

      // Check for reserved filenames
      const fileName = file.name.toUpperCase()
      if (RESERVED_FILENAMES.some((reserved) => fileName.startsWith(reserved))) {
        Alert.alert(
          "Invalid Filename",
          "This filename is not allowed. Please choose a different name.",
        )
        return
      }

      // Check file size (256MB limit from backend)
      const MAX_FILE_SIZE = 256 * 1024 * 1024 // 256MB
      if (file.size && file.size > MAX_FILE_SIZE) {
        Alert.alert("File Too Large", "The file size exceeds the maximum limit of 256MB.")
        return
      }

      // For iOS, we need to handle the file URI differently
      let fileUri = file.uri
      if (Platform.OS === "ios") {
        // Remove the file:// prefix if it exists
        fileUri = fileUri.replace("file://", "")
      }

      // Create a File object from the picked document
      const fileObj = new File([fileUri], file.name, {
        type: file.mimeType || "application/octet-stream",
      })

      addFile.mutate(
        { folderId: item.id!, file: fileObj },
        {
          onError: () => {
            Alert.alert(
              "Upload Failed",
              "Failed to upload file. Please try again or choose a different file.",
            )
          },
        },
      )
    } catch (error) {
      console.error("File upload error:", error)
      Alert.alert(
        "Error",
        "Failed to upload file. Please make sure the file is in a supported format and try again.",
      )
    }
  }

  return (
    <BareAccordion
      key={item.id}
      isExpanded={selectedFolderId === item.id}
      onToggle={() => onFolderPress(item.id!)}
      style={$folderAccordion}
      disabled={isLoading}
      wrapperStyleOverride={$folderAccordionWrapper}
      triggerNode={({ animatedChevron }) => (
        <SimpleContextMenu
          actions={[
            {
              key: "attach",
              title: isAttached ? "Detach from Agent" : "Attach to Agent",
              iosIconName: { name: isAttached ? "minus.circle" : "plus.circle", weight: "bold" },
              androidIconName: isAttached ? "ic_menu_remove" : "ic_menu_add",
              onPress: handleToggleFolder,
            },
            {
              key: "upload",
              title: "Upload File",
              iosIconName: { name: "arrow.up.doc", weight: "bold" },
              androidIconName: "ic_menu_upload",
              onPress: handleUploadFile,
            },
            {
              key: "delete",
              title: "Delete",
              iosIconName: { name: "trash", weight: "bold" },
              androidIconName: "ic_menu_delete",
              onPress: handleDelete,
            },
          ]}
        >
          <View style={themed($folderHeader)}>
            <View style={$folderInfo}>
              <Text text={item.name || "Unnamed Folder"} preset="subheading" />
              <Text
                text={item.description || "No description"}
                preset="formHelper"
                numberOfLines={2}
              />
            </View>
            {animatedChevron}
          </View>
        </SimpleContextMenu>
      )}
    >
      <View style={$filesContainer}>
        <ScrollView contentContainerStyle={$filesContainerContent}>
          {!files?.length ? (
            <EmptyState heading="No files found" content="Add files to this folder" icon="File" />
          ) : (
            files?.map((file) => (
              <FileCard key={file.id} item={file} selectedFolderId={selectedFolderId} />
            ))
          )}
        </ScrollView>
      </View>
    </BareAccordion>
  )
}

export const FolderManagerScreen = () => {
  const { themed } = useAppTheme()
  const { data: folders, isLoading, refetch } = useGetFolders()
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(null)
  const { data: files } = useGetFolderFiles(selectedFolderId || "")
  const [searchQuery, setSearchQuery] = useState("")
  const [agentId] = useAgentId()
  const { data: agentFolders } = useGetAgentFolders(agentId || "")

  const handleFolderPress = (folderId: string) => {
    setSelectedFolderId(selectedFolderId === folderId ? null : folderId)
  }

  const handleRefresh = (event: GestureResponderEvent) => {
    event.preventDefault()
    refetch()
  }

  const filteredAndSortedFolders = useMemo(() => {
    if (!folders) return []

    const query = searchQuery.toLowerCase()
    const filtered = folders.filter(
      (folder) =>
        folder.name?.toLowerCase().includes(query) ||
        folder.description?.toLowerCase().includes(query),
    )

    return filtered.sort((a, b) => {
      // First sort by attachment status
      const aIsAttached = agentFolders?.some((s) => s.id === a.id) ?? false
      const bIsAttached = agentFolders?.some((s) => s.id === b.id) ?? false
      if (aIsAttached !== bIsAttached) {
        return aIsAttached ? -1 : 1
      }

      // Then sort by date (newest first)
      return (
        (new Date(b.updated_at || "").getTime() || 0) -
        (new Date(a.updated_at || "").getTime() || 0)
      )
    })
  }, [folders, searchQuery, agentFolders])

  const [AnimatedIsVisible, Loader] = useAnimatedLoader()
  useEffect(() => {
    AnimatedIsVisible.value = isLoading
  }, [AnimatedIsVisible, isLoading])

  const { bottom, top } = useSafeAreaInsets()
  return (
    <Screen
      preset="fixed"
      style={themed($screen)}
      contentContainerStyle={[
        themed($contentContainer),
        { paddingBottom: bottom, paddingTop: top },
      ]}
    >
      <View style={$headerContainer}>
        <Text text="Filesystem / Folders" preset="subheading" />
        <Button onPress={handleRefresh} style={$refreshButton}>
          <Icon icon="RotateCcw" size={16} />
        </Button>
      </View>

      <View style={$searchContainer}>
        <TextField
          value={searchQuery}
          onChangeText={setSearchQuery}
          placeholder="Search folders..."
        />
      </View>

      <View style={$listContainer}>
        <Loader />
        <ScrollView>
          {!filteredAndSortedFolders?.length ? (
            <EmptyState
              heading="No folders found"
              content={searchQuery ? "No folders match your search" : "Add a folder to get started"}
              button="Refresh"
              buttonOnPress={handleRefresh}
              icon="FileStack"
            />
          ) : (
            filteredAndSortedFolders?.map((folder) => (
              <FolderAccordion
                key={folder.id}
                item={folder}
                selectedFolderId={selectedFolderId}
                onFolderPress={handleFolderPress}
                files={files}
              />
            ))
          )}
        </ScrollView>
      </View>
    </Screen>
  )
}

const $screen: ViewStyle = {
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

const $folderAccordion: ViewStyle = {
  marginBottom: spacing.sm,
}

const $folderAccordionWrapper: ViewStyle = {
  padding: 0,
}

const $folderHeader: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  padding: spacing.sm,
  borderWidth: 1,
  borderColor: colors.elementColors.card.default.borderColor,
})

const $folderInfo: ViewStyle = {
  flex: 1,
  width: "100%",
}

const $filesContainer: ViewStyle = {
  flex: 1,
  paddingHorizontal: -spacing.sm,
}

const $filesContainerContent: ViewStyle = {
  gap: spacing.sm,
  paddingTop: spacing.sm,
}

const $fileCard: ThemedStyle<ViewStyle> = ({ colors }) => ({
  borderColor: colors.elementColors.card.default.borderColor,
  borderWidth: 1,
  width: "100%",
})

const $refreshButton: ViewStyle = {
  justifyContent: "center",
  alignItems: "center",
  padding: 0,
  margin: 0,
  minWidth: 0,
}

const $fileContentContainer: ViewStyle = {
  gap: spacing.xs,
}

const $fileMetadataContainer: ViewStyle = {
  flexDirection: "row",
  flexWrap: "wrap",
  gap: spacing.xs,
}

const $fileMetadataRow: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xxxs,
}

const $metadataLabel: TextStyle = {
  opacity: 0.7,
}

const $searchContainer: ViewStyle = {
  marginBottom: spacing.sm,
}

// Helper functions
const formatFileSize = (bytes?: number) => {
  if (!bytes) return "Unknown"
  const units = ["B", "KB", "MB", "GB"]
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(1)} ${units[unitIndex]}`
}

const formatDate = (dateString?: string) => {
  if (!dateString) return "Unknown"
  try {
    return new Intl.DateTimeFormat("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
    }).format(new Date(dateString))
  } catch {
    return "Invalid date"
  }
}
