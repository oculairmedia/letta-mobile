import { Button, Card, Screen, Text } from "@/components"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import { spacing } from "@/theme"
import { FC, useMemo } from "react"
import { FlatList, View, ViewStyle } from "react-native"

export const ConfigListScreen: FC = () => {
  const { configs, activeConfigId, setActiveConfig, deleteConfig } = useLettaConfigStore()

  useLettaHeader()

  const sortedConfigs = useMemo(() => {
    return [...configs].sort((a, b) => (b.id > a.id ? 1 : -1))
  }, [configs])

  return (
    <Screen style={$root} preset="fixed" contentContainerStyle={$contentContainer}>
      <FlatList
        data={sortedConfigs}
        keyExtractor={(item) => item.id}
        ItemSeparatorComponent={() => <View style={{ height: spacing.sm }} />}
        renderItem={({ item }) => (
          <SimpleContextMenu
          // actions={[
          //   {
          //     key: "developer",
          //     title: "Developer Settings",
          //     iosIconName: { name: "gear", weight: "bold" },
          //     androidIconName: "ic_menu_manage",
          //     onPress: () => navigate("Developer"),
          //   },
          // ]}
          >
            <Card
              heading={item.mode === "cloud" ? "Cloud" : "Self-hosted"}
              ContentComponent={
                <View style={$configContent}>
                  {item.mode === "selfhosted" && <Text size="xs">Server: {item.serverUrl}</Text>}
                  <Text size="xs">Access Token: {item.accessToken.replace(/.(?=.{4})/g, "*")}</Text>
                  <View style={$actionsRow}>
                    <Button
                      preset={item.id === activeConfigId ? "filled" : "default"}
                      text={item.id === activeConfigId ? "Active" : "Set Active"}
                      onPress={() => setActiveConfig(item.id)}
                      style={$actionButton}
                    />
                    <Button
                      preset="destructive"
                      text="Delete"
                      onPress={() => deleteConfig(item.id)}
                      style={$actionButton}
                    />
                  </View>
                </View>
              }
            />
          </SimpleContextMenu>
        )}
        contentContainerStyle={{ padding: spacing.sm }}
        ListEmptyComponent={<Text text="No configurations found." />}
      />
    </Screen>
  )
}

const $root: ViewStyle = {
  flex: 1,
}

const $contentContainer: ViewStyle = {
  flex: 1,
  paddingBottom: spacing.lg,
}

const $configContent: ViewStyle = {
  gap: spacing.xs,
}

const $actionsRow: ViewStyle = {
  flexDirection: "row",
  gap: spacing.sm,
  marginTop: spacing.sm,
}

const $actionButton: ViewStyle = {
  flex: 1,
}
