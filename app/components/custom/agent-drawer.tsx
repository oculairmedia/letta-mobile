import { useAgentId } from "@/hooks/use-agentId-param"
import { useAgentChatContext } from "@/hooks/use-chat-context"
import { useAgentStore } from "@/providers/AgentProvider"
import { AgentName } from "@/shared/components/agent-settings"
import { spacing } from "@/theme"
import { DrawerContentComponentProps, DrawerContentScrollView } from "@react-navigation/drawer"
import { View, ViewStyle } from "react-native"
import { AgentActions } from "./agent-actions"
import { ContextWindow } from "./context-window/context-window"
import { MemoryBlocks } from "./memory-blocks"

export function AgentDrawerContent({ descriptors, state, ...rest }: DrawerContentComponentProps) {
  const focusedRoute = state.routes[state.index]
  const focusedDescriptor = descriptors[focusedRoute.key]
  const focusedOptions = focusedDescriptor.options

  const { drawerContentStyle, drawerContentContainerStyle } = focusedOptions

  const [agentId] = useAgentId()
  const conversationId = useAgentStore((s) => s.conversationId)

  return (
    <DrawerContentScrollView
      {...rest}
      contentContainerStyle={[drawerContentContainerStyle]}
      style={[drawerContentStyle, $containerStyle]}
    >
      <AgentName style={$agentNameContainer} />
      <ChatContext agentId={agentId} />

      <View style={$actionsContainer}>
        <AgentActions agentId={agentId} conversationId={conversationId} />
        <MemoryBlocks agentId={agentId} />
      </View>
    </DrawerContentScrollView>
  )
}

const ChatContext = ({ agentId }: { agentId: string }) => {
  const { data: chatContext } = useAgentChatContext(agentId)
  return <ContextWindow contextData={chatContext} />
}

const $containerStyle: ViewStyle = {
  paddingHorizontal: spacing.md,
}

const $agentNameContainer: ViewStyle = {
  paddingHorizontal: 0,
  backgroundColor: undefined,
}

const $actionsContainer: ViewStyle = {
  flexDirection: "column",
  gap: spacing.md,
}
