import { ChatToolbarInner } from "./chat-toolbar"

import { useAgentMessages } from "@/hooks/use-agent-messages"
import { useAgentId } from "@/hooks/use-agentId-param"
import { useSendMessageAsync } from "@/hooks/use-send-message"
import { useAgentStore } from "@/providers/AgentProvider"

import { Card } from "@/components/Card"
import { AppMessage, MESSAGE_TYPE } from "@/hooks/types"
import { AssistantMessage } from "@/shared/components/messages/assistant-message"
import { MessageSkeleton } from "@/shared/components/messages/message-skeleton"
import { ReasoningMessage } from "@/shared/components/messages/reasoning-message"
import { ToolInteraction } from "@/shared/components/messages/tool-interaction"
import { UserMessage } from "@/shared/components/messages/user-message"
import { colors, spacing } from "@/theme"
import { Brain, HelpCircle, Lightbulb, Loader, Sparkles } from "lucide-react-native"
import { Fragment, useCallback, useEffect, useRef, useState } from "react"
import {
  FlatList,
  ImageStyle,
  ScrollView,
  Text,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native"
import Animated, {
  SharedValue,
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated"
import { EmptyState } from "../EmptyState"
import { IconTypes } from "../Icon"
import { ChatModeControl } from "./chat-mode-control"
import { AgentToolsButton } from "./agent-tools-panel"
import { ConversationSelector } from "./conversation-selector"

const useScrollToBottom = (ref: React.RefObject<FlatList>) => {
  const scrollToBottom = useCallback(() => {
    if (ref.current) {
      setTimeout(() => {
        ref.current?.scrollToEnd({ animated: true })
      }, 100)
    }
  }, [ref])

  return scrollToBottom
}

const JsonBlock = ({ value }: { value: any }) => (
  <Card
    ContentComponent={
      <ScrollView horizontal>
        <Text style={$jsonText}>{JSON.stringify(value, null, 2)}</Text>
      </ScrollView>
    }
  />
)

const MODES = ["simple", "interactive", "debug"] as const
export type ChatUIMode = (typeof MODES)[number]

const MODE_ICONS: Record<ChatUIMode, IconTypes> = {
  debug: "Code",
  interactive: "MessageSquareText",
  simple: "MessageSquare",
}

// --- Mode-specific renderers ---
const renderSimpleItem = (item: AppMessage) => {
  if (item.messageType === MESSAGE_TYPE.USER_MESSAGE) {
    return <UserMessage key={item.id} content={item.content} footer={item.messageType} />
  }
  if (item.messageType === MESSAGE_TYPE.ASSISTANT_MESSAGE) {
    return <AssistantMessage key={item.id} content={item.content} footer={item.messageType} />
  }
  return null
}

const renderDebugItem = (item: AppMessage) => {
  return <JsonBlock key={item.id} value={item} />
}

const renderInteractiveItem = (item: AppMessage, _data: AppMessage[]) => {
  if (item.messageType === MESSAGE_TYPE.USER_MESSAGE) {
    return <UserMessage key={item.id} content={item.content} footer={item.messageType} />
  }
  if (item.messageType === MESSAGE_TYPE.REASONING_MESSAGE) {
    return <ReasoningMessage key={item.id} content={item.content} footer={item.messageType} />
  }
  if (item.messageType === MESSAGE_TYPE.ASSISTANT_MESSAGE) {
    return <AssistantMessage key={item.id} content={item.content} footer={item.messageType} />
  }
  if (item.messageType === MESSAGE_TYPE.TOOL_MESSAGE) {
    return <ToolInteraction key={item.id} message={item} />
  }
  return null
}
// --- End mode-specific renderers ---

const ITEM_MARGIN = spacing.md

const renderItem = ({
  item,
  data,
  mode,
}: {
  item: AppMessage
  data: AppMessage[]
  mode: ChatUIMode
}) => {
  let content = null
  if (mode === "simple") content = renderSimpleItem(item)
  else if (mode === "debug") content = renderDebugItem(item)
  else if (mode === "interactive") content = renderInteractiveItem(item, data)

  if (!content) return null

  return <View style={{ marginBottom: ITEM_MARGIN }}>{content}</View>
}

const StarterPrompts = () => {
  const [agentId] = useAgentId()
  const conversationId = useAgentStore((s) => s.conversationId)
  const { mutate: sendMessage } = useSendMessageAsync()

  const prompts = [
    { text: "What can you help me with?", icon: Sparkles },
    { text: "Tell me about your capabilities", icon: Brain },
    { text: "How do I get started?", icon: HelpCircle },
    { text: "What are your limitations?", icon: Lightbulb },
  ]

  return (
    <View style={$starterPromptsContainer}>
      <View style={$promptsContainer}>
        {prompts.map((prompt, index) => (
          <TouchableOpacity
            key={index}
            style={$promptButton}
            onPress={() => sendMessage({ agentId, text: prompt.text, conversationId })}
          >
            <Card
              style={$promptCard}
              ContentComponent={
                <View style={$promptContent}>
                  <prompt.icon size={20} color={colors.tint} style={$promptIcon} />
                  <Text style={$promptText}>{prompt.text}</Text>
                </View>
              }
            />
          </TouchableOpacity>
        ))}
      </View>
    </View>
  )
}

const EmptyChatState = () => {
  const [agentId] = useAgentId()
  const conversationId = useAgentStore((s) => s.conversationId)
  const { mutate: sendMessage } = useSendMessageAsync()
  return (
    <EmptyState
      heading="No messages yet"
      content="Start a conversation with the agent"
      button="Start a conversation"
      buttonOnPress={() => sendMessage({ agentId, text: "Hello", conversationId })}
    />
  )
}

function MessagesScrollView({ mode }: { mode: ChatUIMode }) {
  const [agentId] = useAgentId()
  const conversationId = useAgentStore((s) => s.conversationId)
  const { data: messages, isLoading: isLoadingMessages } = useAgentMessages(agentId, conversationId)
  const flatListRef = useRef<FlatList>(null!)
  const scrollToBottom = useScrollToBottom(flatListRef)
  useEffect(() => {
    if (messages?.length) {
      scrollToBottom()
    }
  }, [messages, scrollToBottom])
  if (!messages?.length) {
    return <StarterPrompts />
  }
  return (
    <FlatList
      ref={flatListRef}
      contentInsetAdjustmentBehavior="automatic"
      keyboardDismissMode="interactive"
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}
      data={messages}
      renderItem={({ item }) => renderItem({ item, data: messages || [], mode })}
      keyExtractor={(item) => item.id}
      ListEmptyComponent={
        isLoadingMessages ? (
          <Fragment>
            {["user", "assistant", "user", "assistant", "assistant", "user", "assistant"].map(
              (type, index) => (
                <MessageSkeleton key={index} isUser={type === "user"} index={index} />
              ),
            )}
          </Fragment>
        ) : (
          <EmptyChatState />
        )
      }
      onContentSizeChange={scrollToBottom}
      onLayout={scrollToBottom}
      maintainVisibleContentPosition={{
        minIndexForVisible: 0,
        autoscrollToTopThreshold: 10,
      }}
    />
  )
}

export function ChatUI({ mode: initialMode = "interactive" }: { mode?: ChatUIMode }) {
  const [mode, setMode] = useState<ChatUIMode>(initialMode)
  return (
    <ChatUIContainer>
      <View style={$chatHeader}>
        <View style={$chatHeaderLeft}>
          <ConversationSelector />
          <AgentToolsButton />
        </View>
        <ChatModeControl mode={mode} setMode={setMode} MODES={MODES} MODE_ICONS={MODE_ICONS} />
      </View>
      <MessagesScrollView mode={mode} />
      <ChatToolbar />
    </ChatUIContainer>
  )
}

const $chatHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
}

const $chatHeaderLeft: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xs,
  flex: 1,
}

const ChatUIContainer = ({ children }: { children: React.ReactNode }) => {
  return <View style={$chatUIContainer}>{children}</View>
}

const $chatUIContainer: ViewStyle = {
  flex: 1,
  flexDirection: "column",
  gap: spacing.md,
}

function ChatToolbar() {
  const [agentId] = useAgentId()
  const conversationId = useAgentStore((s) => s.conversationId)
  const { mutate: sendMessage, isPending } = useSendMessageAsync()
  const onSubmit = (value: string) => {
    console.log("onSubmit:ChatToolbar", value)
    sendMessage({ agentId, text: value, conversationId })
  }

  const AnimatedIsVisible = useSharedValue(false)
  useEffect(() => {
    AnimatedIsVisible.value = isPending
  }, [isPending, AnimatedIsVisible])
  return (
    <Fragment>
      <ChatToolbarInner onSubmit={onSubmit} />
      <AnimatedLoader isVisible={AnimatedIsVisible} />
    </Fragment>
  )
}

const AnimatedLoader = ({ isVisible }: { isVisible: SharedValue<boolean> }) => {
  const rotation = useDerivedValue(() => {
    if (isVisible.value) {
      return withRepeat(withTiming(360, { duration: 1000 }), -1, false)
    }
    return withTiming(0, { duration: 200 })
  })

  const animatedStyle = useAnimatedStyle(() => {
    return {
      transform: [
        { translateY: withTiming(isVisible.value ? -0.25 : 0, { duration: 200 }) },
        { rotate: `${rotation.value}deg` },
      ],
      opacity: withTiming(isVisible.value ? 1 : 0, { duration: 200 }),
    }
  })

  return (
    <Animated.View style={[animatedStyle, $loaderStyle]}>
      <Loader size={24} color={colors.tint} />
    </Animated.View>
  )
}

const $loaderStyle: ImageStyle = {
  position: "absolute",
  bottom: spacing.xxxl,
  end: spacing.md,
}

const $starterPromptsContainer: ViewStyle = {
  flex: 1,
  justifyContent: "center",
  alignItems: "center",
  paddingHorizontal: spacing.lg,
  gap: spacing.md,
}

const $promptsContainer: ViewStyle = {
  width: "100%",
  gap: spacing.sm,
}

const $promptButton: ViewStyle = {
  width: "100%",
}

const $promptCard: ViewStyle = {
  paddingHorizontal: spacing.md,
  paddingVertical: spacing.sm,
  backgroundColor: colors.transparent,
  borderColor: colors.transparent,
  minHeight: 0,
}

const $promptContent: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.sm,
}

const $promptIcon: ImageStyle = {
  marginRight: spacing.xs,
}

const $promptText: TextStyle = {
  color: colors.tint,
  fontSize: 16,
  flex: 1,
}

const $jsonText: TextStyle = {
  color: colors.tint,
  fontFamily: "monospace",
  fontSize: 12,
}
