import { Card, Icon, Screen, Text } from "@/components"
import { AutoImage } from "@/components/AutoImage"
import { Badge } from "@/components/Badge"
import { Button } from "@/components/Button"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { useAgents } from "@/hooks/use-agents"
import { STARTER_KITS, useCreateAgent } from "@/hooks/use-create-agent"
import { useDeleteAgent } from "@/hooks/use-delete-agent"
import { AppStackScreenProps, navigate } from "@/navigators"
import { useAgentStore } from "@/providers/AgentProvider"
import { normalizeName } from "@/shared/utils/normalizers"
import { spacing, ThemedStyle } from "@/theme"
import { showAgentNamePrompt } from "@/utils/agent-name-prompt"
import { useAppTheme } from "@/utils/useAppTheme"
import { Letta } from "@letta-ai/letta-client"
import { FC, Fragment, useMemo, useState } from "react"
import {
  Alert,
  FlatList,
  ImageStyle,
  RefreshControl,
  TextInput,
  TextStyle,
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

  const customTools = useMemo(() => {
    return agent.tools.filter((t) => t.tool_type === "custom")
  }, [agent.tools])

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
        heading={agent.name || "Unnamed Agent"}
        ContentComponent={
          <View style={$agentContentContainer}>
            <Text style={themed($agentContentTextStyle)} numberOfLines={2}>
              {agent.description}
            </Text>
            <View style={$agentContentFooter}>
              <Text size="xxs">
                last usage:{" "}
                {new Intl.DateTimeFormat("en-US", {
                  year: "numeric",
                  month: "short",
                  day: "numeric",
                  hour: "numeric",
                  minute: "numeric",
                }).format(new Date(agent.updated_at || ""))}
              </Text>
            </View>
            <View style={$agentMetadataContainer}>
              {!!agent.tags.length && (
                <View style={$toolBadgesContainer}>
                  {agent.tags.map((tag) => (
                    <Badge key={tag} text={tag} style={themed($badgeStyle)} />
                  ))}
                </View>
              )}
              <View style={$statsContainer}>
                <View style={$toolBadgesContainer}>
                  {customTools.map((t) => (
                    <Badge key={t.name} text={normalizeName(t.name || "")} />
                  ))}
                </View>
                <View style={$statsRow}>
                  <Text size="xxs">{agent.model}</Text>
                  <Text size="xxs">{agent.blocks.length} blocks</Text>
                </View>
              </View>
            </View>
          </View>
        }
        RightComponent={
          <Icon icon="caretRight" size={20} color={colors.elementColors.card.default.content} />
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
                  source={{ uri: `https://app.letta.com${kit.image.src}` }}
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

    if (!searchQuery.trim()) return sorted

    const query = searchQuery.toLowerCase().trim()
    return sorted?.filter((agent) => {
      const nameMatch = agent.name?.toLowerCase().includes(query)
      const descMatch = agent.description?.toLowerCase().includes(query)
      const tagMatch = agent.tags?.some((tag) => tag.toLowerCase().includes(query))
      return nameMatch || descMatch || tagMatch
    })
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
            <Icon
              icon="X"
              size={18}
              color={colors.textDim}
              onPress={() => setSearchQuery("")}
            />
          )}
        </View>
        <View style={$headerRow}>
          <Button
            onPress={() => navigate("Studio")}
            text="Studio"
            style={$headerButton}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon
                icon="FlaskConical"
                size={20}
                color={colors.elementColors.card.default.content}
              />
            )}
          />
          <Button
            onPress={() => {
              showAgentNamePrompt({
                onSubmit: (name) => createAgent({ name }),
              })
            }}
            text="New Agent"
            style={$headerButton}
            loading={isCreatingAgent}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon icon="Bot" size={20} color={colors.elementColors.card.default.content} />
            )}
          />
        </View>
        <View style={$headerRow}>
          <Button
            onPress={() => navigate("Templates")}
            text="Kits"
            style={$headerButton}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon icon="FileStack" size={20} color={colors.elementColors.card.default.content} />
            )}
          />
          <Button
            onPress={() => navigate("MCP")}
            text="MCP"
            style={$headerButton}
            disabled={isCreatingAgent}
            LeftAccessory={() => (
              <Icon icon="Server" size={20} color={colors.elementColors.card.default.content} />
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
        ItemSeparatorComponent={() => <View style={{ height: spacing.sm }} />}
        renderItem={({ item }) => (
          <AgentCard
            agent={item}
            onPress={() => {
              chatWithAgent(item.id)
            }}
          />
        )}
        contentContainerStyle={{ padding: spacing.sm }}
        ListEmptyComponent={<StarterKits />}
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

const $agentContentTextStyle: ThemedStyle<TextStyle> = () => ({
  opacity: 0.8,
})

const $agentContentFooter: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $agentContentContainer: ViewStyle = {
  flex: 1,
}

const $toolBadgesContainer: ViewStyle = {
  flexDirection: "row",
  flexWrap: "wrap",
  gap: spacing.xxs,
}

const $agentMetadataContainer: ViewStyle = {
  gap: spacing.xs,
  marginTop: spacing.xs,
}

const $statsContainer: ViewStyle = {
  gap: spacing.xxs,
}

const $statsRow: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $badgeStyle: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.elementColors.button.filled.backgroundColor,
  opacity: 0.7,
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
