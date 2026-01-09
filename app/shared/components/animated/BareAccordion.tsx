import { ButtonProps } from "@/components"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { ChevronDown } from "lucide-react-native"
import {
  StyleProp,
  TextStyle,
  TouchableOpacity,
  TouchableOpacityProps,
  View,
  ViewStyle,
} from "react-native"
import Animated, {
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withTiming,
} from "react-native-reanimated"
export interface CardAccordionItemProps {
  /**
   * The content to be displayed inside the accordion
   */
  children: React.ReactNode
  /**
   * An optional style override for the container
   */
  style?: StyleProp<ViewStyle>
  /**
   * The duration of the animation in milliseconds
   */
  duration?: number
  /**
   * Whether the accordion is expanded
   */
  isExpanded: boolean
  /**
   * An optional style override for the wrapper
   */
  $wrapperStyleOverride?: StyleProp<ViewStyle>
}

export interface CardAccordionProps extends Omit<ButtonProps, "preset"> {
  /**
   * The content to be displayed inside the accordion
   */
  children: React.ReactNode
  /**
   * Whether the accordion is expanded
   */
  isExpanded: boolean
  /**
   * Callback when the accordion is toggled
   */
  onToggle: () => void
  /**
   * The duration of the animation in milliseconds
   */
  duration?: number
  /**
   * An optional style override for the container
   */
  style?: StyleProp<ViewStyle>
  /**
   * Whether the accordion is disabled
   */
  disabled?: boolean
  /**
   * Button Style
   */
  buttonStyle?: ButtonProps["style"]
  /**
   * Trigger Node
   */
  triggerNode?: ({ animatedChevron }: { animatedChevron: React.ReactNode }) => React.ReactNode
  /**
   * Touchable Opacity Props
   */
  touchableOpacityProps?: Omit<TouchableOpacityProps, "onPress" | "disabled">
  /**
   * An optional style override for the wrapper
   */
  wrapperStyleOverride?: StyleProp<ViewStyle>
}

export function CardAccordionItem({
  children,
  style,
  duration = 300,
  isExpanded,
  $wrapperStyleOverride,
}: CardAccordionItemProps) {
  const height = useSharedValue(0)

  const derivedHeight = useDerivedValue(() =>
    withTiming(height.value * Number(isExpanded), {
      duration,
    }),
  )

  const bodyStyle = useAnimatedStyle(() => ({
    height: derivedHeight.value,
  }))

  return (
    <Animated.View style={[$animatedView, bodyStyle, style]}>
      <View
        onLayout={(e) => {
          height.value = e.nativeEvent.layout.height
        }}
        style={[$wrapper, $wrapperStyleOverride]}
      >
        {children}
      </View>
    </Animated.View>
  )
}

export function BareAccordion({
  children,
  isExpanded,
  onToggle,
  duration = 300,
  disabled,
  style,
  touchableOpacityProps,
  triggerNode,
  wrapperStyleOverride: $wrapperStyleOverride,
}: CardAccordionProps) {
  const { themed } = useAppTheme()

  return (
    <View style={[$container, style]}>
      <TouchableOpacity onPress={onToggle} disabled={disabled} {...touchableOpacityProps}>
        {triggerNode?.({
          animatedChevron: (
            <Animated.View style={{ transform: [{ rotate: isExpanded ? "180deg" : "0deg" }] }}>
              <ChevronDown size={20} color={themed($chevronIcon).color} />
            </Animated.View>
          ),
        })}
      </TouchableOpacity>

      <CardAccordionItem
        isExpanded={isExpanded}
        duration={duration}
        $wrapperStyleOverride={$wrapperStyleOverride}
      >
        {children}
      </CardAccordionItem>
    </View>
  )
}

const $chevronIcon: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.tint,
})

const $container: ViewStyle = {
  overflow: "hidden",
}

const $wrapper: ViewStyle = {
  width: "100%",
  position: "absolute",
  padding: spacing.sm,
}

const $animatedView: ViewStyle = {
  width: "100%",
  overflow: "hidden",
}
