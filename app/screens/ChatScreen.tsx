import { Screen } from "@/components"
import { ChatUI } from "@/components/custom/chat-ui"
import { useLettaHeader } from "@/components/custom/useLettaHeader"
import { AgentTabScreenProps } from "@/navigators"
import { spacing } from "@/theme"
import { FC, ReactNode, useState } from "react"
import { KeyboardAvoidingView, KeyboardAvoidingViewProps, Platform, ViewStyle } from "react-native"
import { useKeyboardHandler } from "react-native-keyboard-controller"
import { runOnJS } from "react-native-reanimated"

interface ChatScreenProps extends AgentTabScreenProps<"Chat"> {}

export const ChatScreen: FC<ChatScreenProps> = () => {
  useLettaHeader()
  return (
    <Screen style={$root} contentContainerStyle={$contentContainer} preset="fixed">
      <KeyboardHandlerView>
        <ChatUI />
      </KeyboardHandlerView>
    </Screen>
  )
}

interface KeyboardHandlerViewProps {
  children: ReactNode
  KeyboardAvoidingViewProps?: KeyboardAvoidingViewProps
}

const KeyboardHandlerView = ({ children, KeyboardAvoidingViewProps }: KeyboardHandlerViewProps) => {
  const isIos = Platform.OS === "ios"
  const [keyboardHeight, setKeyboardHeight] = useState(0)

  useKeyboardHandler({
    onStart: (e) => {
      "worklet"
      runOnJS(setKeyboardHeight)(e.height / 2.6)
    },
    onEnd: () => {
      "worklet"
      runOnJS(setKeyboardHeight)(0)
    },
  })

  return (
    <KeyboardAvoidingView
      behavior={isIos ? "padding" : "height"}
      keyboardVerticalOffset={keyboardHeight}
      {...KeyboardAvoidingViewProps}
      style={[$keyboardAvoidingView, KeyboardAvoidingViewProps?.style]}
    >
      {children}
    </KeyboardAvoidingView>
  )
}

const $root: ViewStyle = {
  flex: 1,
  paddingHorizontal: spacing.sm,
}

const $contentContainer: ViewStyle = {
  flex: 1,
}

const $keyboardAvoidingView: ViewStyle = {
  flex: 1,
}
