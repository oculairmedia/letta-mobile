import { Button, Screen, Text, TextField } from "@/components"
import { EmptyState } from "@/components/EmptyState"
import { CustomToolCard } from "@/components/custom/tool-card"
import { useLettaComposioTools } from "@/hooks/use-letta-tools"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FlashList } from "@shopify/flash-list"
import { FC, useMemo, useState } from "react"
import { Image, ImageStyle, Modal, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface ComposioToolsModalProps {
  visible: boolean
  onDismiss: () => void
}

export const ComposioToolsModal: FC<ComposioToolsModalProps> = ({ visible, onDismiss }) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()

  const { data: composioTools } = useLettaComposioTools()
  const [search, setSearch] = useState("")

  const allComposioTools = useMemo(() => {
    if (!composioTools) return []
    return composioTools
  }, [composioTools])

  const filteredComposioTools = useMemo(() => {
    const query = search.toLowerCase()
    return allComposioTools.filter((app) => {
      if (!query) return true
      const appName = app[0]?.displayName || app[0]?.name
      const appDescription = app[0]?.description
      return appName?.toLowerCase().includes(query) || appDescription?.toLowerCase().includes(query)
    })
  }, [allComposioTools, search])

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
        <Text text="Composio Tools" preset="subheading" />
        <Button onPress={onDismiss} text="Close" />
        <TextField placeholder="Search" value={search} onChangeText={setSearch} />
        <View style={$listContainer}>
          <FlashList
            data={filteredComposioTools}
            contentContainerStyle={themed($flashListContentContainer)}
            ItemSeparatorComponent={() => <View style={$separator} />}
            disableHorizontalListHeightMeasurement
            renderItem={({ item }) => {
              const app = item[0]
              return (
                <View style={$appCard}>
                  <View style={$appHeading}>
                    <Text text={app.displayName} preset="subheading" />
                    {app.logo?.startsWith("http") && (
                      <Image source={{ uri: app.logo }} style={$logo} />
                    )}
                  </View>

                  {item.map((tool) => (
                    <CustomToolCard
                      key={tool.name}
                      tool={{
                        key: tool.name + tool.appName,
                        name: tool.name || "",
                        description: tool.description,
                      }}
                    />
                  ))}
                </View>
              )
            }}
            ListEmptyComponent={() => (
              <EmptyState
                heading="No Composio apps found"
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

const $logo: ImageStyle = {
  width: 26,
  aspectRatio: 1,
  resizeMode: "contain",
}

const $appHeading: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "space-between",
  gap: spacing.xs,
  marginBottom: spacing.xs,
}

const $appCard: ViewStyle = {}
