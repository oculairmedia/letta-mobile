import { Button, Screen, Text, TextField } from "@/components"
import { EmptyState } from "@/components/EmptyState"
import { CustomToolCard } from "@/components/custom/tool-card"
import { useLettaTools } from "@/hooks/use-letta-tools"
import { AttachToolAction } from "@/shared/components/tools/attach-tool-action"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FlashList } from "@shopify/flash-list"
import { FC, useMemo, useState } from "react"
import { Modal, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface CustomToolsModalProps {
  visible: boolean
  onDismiss: () => void
}

export const CustomToolsModal: FC<CustomToolsModalProps> = ({ visible, onDismiss }) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()
  const { data: tools } = useLettaTools()
  const [search, setSearch] = useState("")

  const filteredTools = useMemo(() => {
    const query = search.toLowerCase()
    return tools?.filter(
      (tool) =>
        tool.name?.toLowerCase().includes(query) || tool.description?.toLowerCase().includes(query),
    )
  }, [tools, search])

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
        <Text text="Custom Tools" preset="subheading" />
        <Button onPress={onDismiss} text="Close" />
        <TextField placeholder="Search" value={search} onChangeText={setSearch} />
        <View style={$listContainer}>
          <FlashList
            data={filteredTools}
            contentContainerStyle={themed($flashListContentContainer)}
            ItemSeparatorComponent={() => <View style={$separator} />}
            estimatedItemSize={117}
            renderItem={({ item }) => (
              <CustomToolCard
                key={item.id}
                tool={{
                  id: item.id,
                  name: item.name || "",
                  description: item.description,
                }}
                RightComponent={
                  <AttachToolAction
                    tool={item}
                    onSuccess={() => {
                      setSearch("")
                    }}
                  />
                }
              />
            )}
            ListEmptyComponent={() => (
              <EmptyState
                heading="No custom tools found"
                content="Please add a tool to your agent"
                icon="Blocks"
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

const $listContainer: ViewStyle = {
  flex: 1,
  width: "100%",
}

const $flashListContentContainer: ViewStyle = {
  paddingVertical: spacing.md,
}

const $separator: ViewStyle = {
  height: spacing.md,
}
