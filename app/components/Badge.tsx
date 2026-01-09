import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { StyleProp, TextStyle, View, ViewStyle } from "react-native"
import { Text } from "./Text"

export interface BadgeProps {
  /**
   * The text to display in the badge
   */
  text: string
  /**
   * Optional style override for the badge container
   */
  style?: StyleProp<ViewStyle>
  /**
   * Optional style override for the badge text
   */
  textStyle?: StyleProp<TextStyle>
}

export function Badge(props: BadgeProps) {
  const { text, style: $styleOverride, textStyle: $textStyleOverride } = props
  const { themed } = useAppTheme()

  return (
    <View style={[themed($container), $styleOverride]}>
      <Text size="xxs" style={[themed($text), $textStyleOverride]}>
        {text}
      </Text>
    </View>
  )
}

const $container: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.elementColors.button.filled.backgroundColor,
  paddingHorizontal: spacing.xs,
  alignSelf: "flex-start",
})

const $text: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.text,
})
