import { Button, Icon, Screen, Text, TextField } from "@/components"
import { Card } from "@/components/Card"
import { EmptyState } from "@/components/EmptyState"
import { useAgentId } from "@/hooks/use-agentId-param"
import {
  useAttachSourceToAgent,
  useDetachSourceFromAgent,
  useGetAgentSources,
  useGetSources,
} from "@/hooks/use-get-sources"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Source } from "@letta-ai/letta-client/api"
import { FlashList } from "@shopify/flash-list"
import { FC, useCallback, useMemo, useState } from "react"
import { Modal, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface SourcesModalProps {
  visible: boolean
  onDismiss: () => void
}

const SourceCard = ({
  agentId,
  source,
  isAttached,
}: {
  agentId: string
  source: Source
  isAttached: boolean
}) => {
  const attachSource = useAttachSourceToAgent()
  const detachSource = useDetachSourceFromAgent()
  const handleToggleSource = useCallback(
    (sourceId: string, isAttached: boolean) => {
      if (isAttached) {
        detachSource.mutate({ agentId, sourceId })
      } else {
        attachSource.mutate({ agentId, sourceId })
      }
    },
    [agentId, attachSource, detachSource],
  )
  const isLoading = useMemo(() => {
    return attachSource.isPending || detachSource.isPending
  }, [attachSource.isPending, detachSource.isPending])

  return (
    <Card
      heading={source.name || "Unnamed Source"}
      content={source.description || "No description"}
      RightComponent={
        <Button
          text={isLoading ? "" : isAttached ? "Detach" : "Attach"}
          onPress={() => handleToggleSource(source.id!, isAttached)}
          preset={isAttached ? "default" : "reversed"}
          loading={isLoading}
        />
      }
    />
  )
}

export const SourcesModal: FC<SourcesModalProps> = ({ visible, onDismiss }) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()
  const [agentId] = useAgentId()
  const [search, setSearch] = useState("")

  const { data: agentSources, refetch: refetchAgentSources } = useGetAgentSources(agentId || "")
  const { data: allSources, refetch: refetchAllSources } = useGetSources()

  const sourcesMap = useMemo(() => {
    const map = new Map<string, boolean>()

    allSources?.forEach((source) => {
      map.set(source.id!, agentSources?.some((s) => s.id === source.id) ?? false)
    })
    return map
  }, [allSources, agentSources])

  const filteredSources = useMemo(() => {
    if (!allSources) return []
    const query = search.toLowerCase()
    return allSources.filter(
      (source) =>
        source.name?.toLowerCase().includes(query) ||
        source.description?.toLowerCase().includes(query),
    )
  }, [sourcesMap])

  const renderItem = useCallback(
    ({ item }: { item: Source }) => (
      <View key={item.id} style={$sourceContainer}>
        <SourceCard
          source={item}
          isAttached={sourcesMap.get(item.id!) ?? false}
          agentId={agentId || ""}
        />
      </View>
    ),
    [sourcesMap],
  )

  const handleRefresh = useCallback(() => {
    refetchAgentSources()
    refetchAllSources()
  }, [refetchAgentSources, refetchAllSources])

  const keyExtractor = useCallback((item: Source) => item.id!, [])

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
          <Text text="Agent Sources" preset="subheading" />
          <Button onPress={handleRefresh} style={$refreshButton}>
            <Icon icon="RotateCcw" size={16} />
          </Button>
        </View>
        <Button onPress={onDismiss} text="Close" />
        <TextField
          placeholder="Search sources"
          value={search}
          onChangeText={setSearch}
          style={$searchInput}
        />
        <View style={$listContainer}>
          <FlashList
            data={filteredSources}
            estimatedItemSize={117}
            bounces={false}
            contentContainerStyle={themed($flashListContentContainer)}
            renderItem={renderItem}
            keyExtractor={keyExtractor}
            ListEmptyComponent={() => (
              <EmptyState
                heading="No sources found"
                content="Add a source to your agent"
                button="Refresh"
                buttonOnPress={() => {
                  refetchAllSources()
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

const $sourceContainer: ViewStyle = {
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
