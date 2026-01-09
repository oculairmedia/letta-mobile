import { Screen, Text } from "@/components"
import { LettaConfigsForm } from "@/components/custom/forms/letta-config"
import { ThemeToggle } from "@/components/custom/theme-toggle"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import { $styles, type ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FC, useState } from "react"
import { TextStyle, View, ViewStyle } from "react-native"
import { AppStackScreenProps } from "../navigators"

interface DeveloperScreenProps extends AppStackScreenProps<"Developer" | "Welcome"> {}

export const DeveloperScreen: FC<DeveloperScreenProps> = () => {
  const { themed } = useAppTheme()
  const { setConfig } = useLettaConfigStore()
  const [isPending, setIsPending] = useState(false)

  useLettaHeader()

  const handleSubmit = async (config: {
    id?: string
    mode: "cloud" | "selfhosted"
    serverUrl?: string
    accessToken: string
  }) => {
    try {
      setIsPending(true)
      const result = setConfig(config)
      console.log("Configuration saved:", result)
    } catch (error) {
      console.error("Failed to save configuration:", error)
    } finally {
      setIsPending(false)
    }
  }

  return (
    <Screen preset="fixed" contentContainerStyle={$styles.flex1}>
      <View style={themed($container)}>
        <Text
          testID="developer-heading"
          style={themed($heading)}
          tx="developerScreen:title"
          preset="heading"
        />
        <Text tx="developerScreen:description" preset="subheading" />
        <ThemeToggle />

        <Text
          testID="config-heading"
          tx="developerScreen:serverDetails"
          preset="formLabel"
          style={themed($subheading)}
        />
        <LettaConfigsForm onSubmit={handleSubmit} isPending={isPending} />
      </View>
    </Screen>
  )
}

const $container: ThemedStyle<ViewStyle> = ({ spacing }) => ({
  flex: 1,
  paddingHorizontal: spacing.lg,
  paddingTop: spacing.lg,
  paddingBottom: spacing.lg,
})

const $heading: ThemedStyle<TextStyle> = ({ spacing }) => ({
  marginBottom: spacing.md,
})

const $subheading: ThemedStyle<TextStyle> = ({ spacing }) => ({
  marginBottom: spacing.lg,
})
