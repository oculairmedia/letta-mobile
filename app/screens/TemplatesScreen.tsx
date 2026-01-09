import { Card, Screen, Text } from "@/components"
import { AutoImage } from "@/components/AutoImage"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { STARTER_KITS, useCreateAgent } from "@/hooks/use-create-agent"
import { AppStackScreenProps } from "@/navigators"
import { spacing } from "@/theme"
import { useNavigation } from "@react-navigation/native"
import { FC, Fragment } from "react"
import { Alert, ImageStyle, TextStyle, View, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { showAgentNamePrompt } from "@/utils/agent-name-prompt"

const StarterKits = () => {
  const navigation = useNavigation()
  const { mutate: createAgentFromTemplate, isPending: isCreatingAgent } = useCreateAgent({
    onSuccess: () => {
      navigation.goBack()
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
                templateId: kit.id,
                memoryBlocks: kit.agentState.memory_blocks,
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
                            templateId: kit.id,
                            memoryBlocks: kit.agentState.memory_blocks,
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

export const TemplatesScreen: FC<AppStackScreenProps<"Templates">> = () => {
  useLettaHeader()
  const { bottom } = useSafeAreaInsets()
  return (
    <Screen style={[$root, { paddingBottom: bottom }]} preset="scroll">
      <StarterKits />
    </Screen>
  )
}

const $root: ViewStyle = {
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
