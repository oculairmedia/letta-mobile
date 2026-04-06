import { Card, Icon, Screen, Text } from "@/components"
import { AutoImage } from "@/components/AutoImage"
import { Button } from "@/components/Button"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useAgents } from "@/hooks/use-agents"
import { STARTER_KITS, useCreateAgent } from "@/hooks/use-create-agent"
import { useDeleteAgent } from "@/hooks/use-delete-agent"
import { AppStackScreenProps, navigate } from "@/navigators"
import { useAgentStore } from "@/providers/AgentProvider"
import { formatRelativeTime } from "@/shared/utils/formatters"
import { spacing, ThemedStyle } from "@/theme"
import { showAgentNamePrompt } from "@/utils/agent-name-prompt"
import { useAppTheme } from "@/utils/useAppTheme"
import { Letta } from "@letta-ai/letta-client"
import Fuse from "fuse.js"
import { FC, Fragment, useMemo, useState } from "react"
import {
  Alert,
  FlatList,
  ImageStyle,
  RefreshControl,
  TextInput,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native"

interface AgentCardProps {
  agent: Letta.AgentState
  onPress: () => void
}

const chatWithAgent = (agentId: string) => {
  useAgentStore.getState().setAgentId(agentId)
  navigate("AgentDrawer", { screen: "AgentTab" })
}

const AgentCard: FC<AgentCardProps> = ({ agent }) => {
  const {
    theme: { colors },
    themed,
  } = useAppTheme()

  const deleteAgent = useDeleteAgent()

  return (
    <SimpleContextMenu
      actions={[
        {
          key: "delete",
          title: "Delete",
          iosIconName: { name: "trash", weight: "bold" },
          androidIconName: "ic_menu_delete",
          onPress: () => {
            deleteAgent.mutate({ agentId: agent.id })
          },
        },
      ]}
    >
      <Card
        onPress={() => {
          chatWithAgent(agent.id)
        }}
        disabled={deleteAgent.isPending}
        style={$compactCard}
        HeadingComponent={
          <View style={$agentHeaderRow}>
            <Text preset="bold" numberOfLines={1} style={$agentName}>
              {agent.name || "Unnamed Agent"}
            </Text>
            <Text size="xxs" style={themed($timeText)}>
              {formatRelativeTime(agent.updated_at)}
            </Text>
          </View>
        }
        ContentComponent={
          <View style={$agentMetaRow}>
            <Text size="xxs" style={themed($modelText)} numberOfLines={1}>
              {agent.model}
            </Text>
          </View>
        }
        RightComponent={
          <Icon icon="caretRight" size={16} color={colors.elementColors.card.default.content} />
        }
      />
    </SimpleContextMenu>
  )
}

const StarterKits = () => {
  const { mutate: createAgentFromTemplate, isPending: isCreatingAgent } = useCreateAgent({
    onSuccess: (data) => {
      chatWithAgent(data.id)
    },
  })

  return (
    <View style={$starterKitsContainer}>
      <Text preset="heading" style={$starterKitsTitle}>
        Starter kits
      </Text>
      <Text style={$starterKitsSubtitle}>
        Choose from a starter pack and customize it in the Studio, or start from scratch
      </Text>

      <View style={$starterKitsGrid}>
        {STARTER_KITS.map((kit) => (
          <Card
            key={kit.id}
            style={$starterKitCard}
            onPress={() => {
              const defaultOptions: Parameters<typeof createAgentFromTemplate>["0"] = {
                name: kit.agentState.title,
                template_id: kit.id,
                memory_blocks: kit.agentState.memory_blocks,
              }
              Alert.prompt(
                "Agent name",
                "Enter a name for your agent",
                [
                  {
                    text: "Use default",
                    onPress: () => {
                      createAgentFromTemplate(defaultOptions)
                    },
                  },
                  {
                    text: "Save Name",
                    isPreferred: true,
                    onPress: () => {
                      showAgentNamePrompt({
                        defaultName: kit.agentState.title,
                        onSubmit: (name) => {
                          createAgentFromTemplate({
                            name,
                            template_id: kit.id,
                            memory_blocks: kit.agentState.memory_blocks,
                          })
                        },
                      })
                    },
                  },
                  {
                    text: "Cancel",
                    style: "cancel",
                  },
                ],
                "plain-text",
                kit.agentState.title,
                undefined,
              )
            }}
            disabled={isCreatingAgent}
            ContentComponent={
              <Fragment>
                <AutoImage
                  source={{ uri: kit.image.blurDataURL }}
                  style={$starterKitImage}
                  maxHeight={120}
                />
                <View style={$starterKitContent}>
                  <Text preset="bold" style={$starterKitTitle} size="sm">
                    {kit.agentState.title}
                  </Text>
                  <Text style={$starterKitDescription} size="xs">
                    {kit.agentState.description}
                  </Text>
                  {kit.tools && (
                    <View style={$toolsContainer}>
                      <Text preset="bold" style={$toolsTitle} size="xxs">
                        Tools included:
                      </Text>
                      <Text size="xxs">{kit.tools.map((t) => t.name).join(", ")}</Text>
                    </View>
                  )}
                </View>
              </Fragment>
            }
          />
        ))}
      </View>
    </View>
  )
}

export const AgentListScreen: FC<AppStackScreenProps<"AgentList">> = () => {
  useLettaHeader()
  const [searchQuery, setSearchQuery] = useState("")

  const { data: _agents, refetch, isFetching } = useAgents()

  const agents = useMemo(() => {
    const sorted = _agents?.sort((a, b) => {
      return (
        (new Date(b.updated_at || "").getTime() ?? 0) -
        (new Date(a.updated_at || "").getTime() ?? 0)
      )
    })

    if (!searchQuery.trim() || !sorted?.length) return sorted

    const fuse = new Fuse(sorted, {
      keys: ["name", "description", "tags"],
      threshold: 0.4,
      ignoreLocation: true,
      minMatchCharLength: 1,
    })

    // Token-based search: split query into words, find agents matching ALL tokens
    const tokens = searchQuery.trim().toLowerCase().split(/\s+/).filter(Boolean)

    if (tokens.length <= 1) {
      return fuse.search(searchQuery.trim()).map((result) => result.item)
    }

    // For multi-word queries, find agents that match all tokens
    const matchingSets = tokens.map(
      (token) => new Set(fuse.search(token).map((result) => result.item.id)),
    )

    // Intersect all sets to get agents matching ALL tokens
    const intersection = matchingSets.reduce(
      (acc, set) => new Set([...acc].filter((id) => set.has(id))),
    )

    return sorted.filter((agent) => intersection.has(agent.id))
  }, [_agents, searchQuery])

  const { mutate: createAgent, isPending: isCreatingAgent } = useCreateAgent({
    onSuccess: (data) => {
      chatWithAgent(data.id)
    },
  })

  const {
    theme: { colors },
    themed,
  } = useAppTheme()

  return (
    <Screen style={$root} preset="fixed" contentContainerStyle={$contentContainer}>
      <View style={$header}>
        <View style={themed($searchContainer)}>
          <Icon icon="Search" size={18} color={colors.textDim} />
          <TextInput
            style={themed($searchInput)}
            placeholder="Search agents..."
            placeholderTextColor={colors.textDim}
            value={searchQuery}
            onChangeText={setSearchQuery}
            autoCapitalize="none"
            autoCorrect={false}
          />
          {searchQuery.length > 0 && (
            <Icon icon="X" size={18} color={colors.textDim} onPress={() => setSearchQuery("")} />
          )}
        </View>
        <View style={$headerRow}>
          <Button
            onPress={() => navigate("Templates")}
            text="Templates"
            style={$headerButton}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon icon="FileStack" size={20} color={colors.elementColors.card.default.content} />
            )}
          />
          <Button
            onPress={() => navigate("Tools")}
            text="Tools"
            style={$headerButton}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon icon="Wrench" size={20} color={colors.elementColors.card.default.content} />
            )}
          />
        </View>
      </View>
      <FlatList
        data={agents}
        bounces={!!agents?.length}
        keyExtractor={(item) => item.id}
        refreshControl={<RefreshControl refreshing={isFetching} onRefresh={refetch} />}
        refreshing={isFetching}
        ItemSeparatorComponent={() => <View style={{ height: spacing.xs }} />}
        renderItem={({ item }) => (
          <AgentCard
            agent={item}
            onPress={() => {
              chatWithAgent(item.id)
            }}
          />
        )}
        contentContainerStyle={{ padding: spacing.sm }}
        ListEmptyComponent={
          searchQuery.trim() ? (
            <View style={$noResultsContainer}>
              <Icon icon="Search" size={48} color={colors.textDim} />
              <Text style={$noResultsText}>No agents found for &quot;{searchQuery}&quot;</Text>
            </View>
          ) : (
            <StarterKits />
          )
        }
      />

      <TouchableOpacity
        style={themed($fab)}
        onPress={() => {
          showAgentNamePrompt({
            onSubmit: (name) => createAgent({ name }),
          })
        }}
        disabled={isCreatingAgent}
        activeOpacity={0.8}
      >
        <Icon icon="Plus" size={24} color="#fff" />
      </TouchableOpacity>
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

const $header: ViewStyle = {
  padding: spacing.sm,
  gap: spacing.sm,
}

const $headerRow: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  gap: spacing.sm,
}

const $headerButton: ViewStyle = {
  flex: 1,
}

const $fab: ThemedStyle<ViewStyle> = ({ colors }) => ({
  position: "absolute",
  bottom: spacing.lg,
  right: spacing.md,
  width: 56,
  height: 56,
  borderRadius: 28,
  backgroundColor: colors.tint,
  alignItems: "center",
  justifyContent: "center",
  elevation: 4,
  shadowColor: "#000",
  shadowOffset: { width: 0, height: 2 },
  shadowOpacity: 0.25,
  shadowRadius: 4,
})

const $starterKitsContainer: ViewStyle = {}

const $starterKitsTitle: TextStyle = {}

const $starterKitsSubtitle: TextStyle = {
  marginBottom: spacing.md,
  opacity: 0.7,
}

const $starterKitsGrid: ViewStyle = {
  flexDirection: "row",
  flexWrap: "wrap",
  gap: spacing.sm,
}

const $starterKitCard: ViewStyle = {
  width: "98%",
  padding: 0,
  overflow: "hidden",
}

const $starterKitImage: ImageStyle = {
  width: "100%",
  height: 120,
  resizeMode: "cover",
}

const $starterKitContent: ViewStyle = {
  padding: spacing.sm,
}

const $starterKitTitle: TextStyle = {
  fontSize: 16,
  textTransform: "capitalize",
}

const $starterKitDescription: TextStyle = {
  fontSize: 14,
  opacity: 0.8,
}

const $toolsContainer: ViewStyle = {
  marginTop: spacing.sm,
}

const $toolsTitle: TextStyle = {
  marginBottom: spacing.xxs,
}

const $compactCard: ViewStyle = {
  minHeight: 0,
  paddingVertical: spacing.xs,
}

const $agentHeaderRow: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  gap: spacing.sm,
}

const $agentName: TextStyle = {
  flex: 1,
}

const $timeText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
})

const $agentMetaRow: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.sm,
  marginTop: spacing.xxs,
}

const $modelText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
  flexShrink: 0,
})

const $searchContainer: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  alignItems: "center",
  backgroundColor: colors.palette.overlay20,
  borderRadius: 8,
  paddingHorizontal: spacing.sm,
  paddingVertical: spacing.xs,
  gap: spacing.xs,
})

const $searchInput: ThemedStyle<TextStyle> = ({ colors }) => ({
  flex: 1,
  fontSize: 16,
  color: colors.text,
  paddingVertical: spacing.xxs,
})

const $noResultsContainer: ViewStyle = {
  alignItems: "center",
  justifyContent: "center",
  paddingVertical: spacing.xxl,
  gap: spacing.sm,
}

const $noResultsText: TextStyle = {
  opacity: 0.6,
  textAlign: "center",
}
