import { Card, CardProps, Text } from "@/components"
import { Badge } from "@/components/Badge"
import { normalizeName } from "@/shared/utils/normalizers"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { FC } from "react"
import { View, ViewStyle } from "react-native"

type ToolType = {
  id?: string
  name: string
  description?: string
  serverName?: string
  serverType?: string
  inputSchema?: Record<string, unknown>
  key?: string
}

interface ToolCardProps {
  tool: ToolType
  RightComponent?: CardProps["RightComponent"]
  onPress?: () => void
  style?: ViewStyle
}

export const CustomToolCard: FC<ToolCardProps> = ({ tool, RightComponent, onPress, style }) => {
  const { themed } = useAppTheme()

  return (
    <Card
      onPress={onPress}
      heading={normalizeName(tool.name)}
      style={[$card, style]}
      ContentComponent={
        <View style={$toolContentContainer}>
          <Text style={themed($toolContentTextStyle)} numberOfLines={2}>
            {tool.description?.trim()}
          </Text>
          <View style={$toolMetadataContainer}>
            {tool.serverName && (
              <View style={$toolBadgesContainer}>
                <Badge text={tool.serverName} />
                {tool.serverType && <Badge text={tool.serverType} />}
              </View>
            )}
          </View>
        </View>
      }
      RightComponent={RightComponent}
    />
  )
}

const $card: ViewStyle = {}

const $toolContentContainer: ViewStyle = {
  gap: spacing.xs,
}

const $toolContentTextStyle: ViewStyle = {
  opacity: 0.8,
}

const $toolMetadataContainer: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $toolBadgesContainer: ViewStyle = {
  flexDirection: "row",
  gap: spacing.xs,
  alignItems: "center",
}
