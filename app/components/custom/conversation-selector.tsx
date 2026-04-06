import { Icon } from "@/components/Icon"
import { Text } from "@/components/Text"
import { useConversations, useCreateConversation } from "@/hooks/use-conversations"
import { useAgentStore } from "@/providers/AgentProvider"
import { spacing, ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Conversation } from "@letta-ai/letta-client/resources/conversations/conversations"
import { FC, useState } from "react"
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  TextStyle,
  TouchableOpacity,
  View,
  ViewStyle,
} from "react-native"

const formatConversationLabel = (conv: Conversation, index: number): string => {
  if (conv.summary) return conv.summary
  const date = conv.last_message_at || conv.created_at
  if (date) {
    return new Intl.DateTimeFormat("en-US", {
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "numeric",
    }).format(new Date(date))
  }
  return `Conversation ${index + 1}`
}

export const ConversationSelector: FC = () => {
  const { themed, theme } = useAppTheme()
  const { colors } = theme
  const [modalVisible, setModalVisible] = useState(false)

  const agentId = useAgentStore((s) => s.agentId)
  const conversationId = useAgentStore((s) => s.conversationId)
  const setConversationId = useAgentStore((s) => s.setConversationId)

  const { data: conversations, isLoading } = useConversations(agentId)
  const { mutate: createConversation, isPending: isCreating } = useCreateConversation()

  const selectedConversation = conversations?.find((c) => c.id === conversationId)
  const displayLabel = selectedConversation
    ? formatConversationLabel(
        selectedConversation,
        conversations?.indexOf(selectedConversation) || 0,
      )
    : "Default"

  const handleSelect = (conv: Conversation | null) => {
    setConversationId(conv?.id || null)
    setModalVisible(false)
  }

  const handleNewConversation = () => {
    createConversation(
      { agentId },
      {
        onSuccess: (newConv) => {
          setConversationId(newConv.id)
          setModalVisible(false)
        },
      },
    )
  }

  return (
    <>
      <TouchableOpacity
        style={themed($selectorButton)}
        onPress={() => setModalVisible(true)}
        activeOpacity={0.7}
      >
        <Icon icon="MessageSquare" size={14} color={colors.textDim} />
        <Text size="xs" numberOfLines={1} style={themed($selectorText)}>
          {displayLabel}
        </Text>
        <Icon icon="ChevronDown" size={14} color={colors.textDim} />
      </TouchableOpacity>

      <Modal
        visible={modalVisible}
        transparent
        animationType="fade"
        onRequestClose={() => setModalVisible(false)}
      >
        <Pressable style={$modalOverlay} onPress={() => setModalVisible(false)}>
          <View style={themed($modalContent)}>
            <View style={$modalHeader}>
              <Text preset="bold">Conversations</Text>
              <TouchableOpacity onPress={() => setModalVisible(false)}>
                <Icon icon="X" size={20} color={colors.text} />
              </TouchableOpacity>
            </View>

            <ScrollView style={$conversationList}>
              {/* Default conversation option */}
              <TouchableOpacity
                style={[
                  themed($conversationItem),
                  !conversationId && themed($conversationItemSelected),
                ]}
                onPress={() => handleSelect(null)}
              >
                <Text size="sm" style={!conversationId ? themed($selectedText) : undefined}>
                  Default (Agent Direct)
                </Text>
              </TouchableOpacity>

              {isLoading ? (
                <View style={$loadingContainer}>
                  <ActivityIndicator size="small" color={colors.tint} />
                </View>
              ) : (
                conversations?.map((conv, index) => (
                  <TouchableOpacity
                    key={conv.id}
                    style={[
                      themed($conversationItem),
                      conversationId === conv.id && themed($conversationItemSelected),
                    ]}
                    onPress={() => handleSelect(conv)}
                  >
                    <Text
                      size="sm"
                      numberOfLines={1}
                      style={conversationId === conv.id ? themed($selectedText) : undefined}
                    >
                      {formatConversationLabel(conv, index)}
                    </Text>
                    {conv.last_message_at && (
                      <Text size="xxs" style={themed($conversationMeta)}>
                        {new Intl.DateTimeFormat("en-US", {
                          month: "short",
                          day: "numeric",
                        }).format(new Date(conv.last_message_at))}
                      </Text>
                    )}
                  </TouchableOpacity>
                ))
              )}
            </ScrollView>

            <TouchableOpacity
              style={themed($newConversationButton)}
              onPress={handleNewConversation}
              disabled={isCreating}
            >
              {isCreating ? (
                <ActivityIndicator size="small" color={colors.tint} />
              ) : (
                <>
                  <Icon icon="Plus" size={16} color={colors.tint} />
                  <Text size="sm" style={themed($newConversationText)}>
                    New Conversation
                  </Text>
                </>
              )}
            </TouchableOpacity>
          </View>
        </Pressable>
      </Modal>
    </>
  )
}

const $selectorButton: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xxs,
  paddingHorizontal: spacing.xs,
  paddingVertical: spacing.xxs,
  backgroundColor: colors.palette.overlay20,
  borderRadius: 6,
  maxWidth: 180,
})

const $selectorText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
  flex: 1,
})

const $modalOverlay: ViewStyle = {
  flex: 1,
  backgroundColor: "rgba(0,0,0,0.5)",
  justifyContent: "center",
  alignItems: "center",
  padding: spacing.lg,
}

const $modalContent: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.background,
  borderRadius: 12,
  width: "100%",
  maxWidth: 400,
  maxHeight: "70%",
})

const $modalHeader: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  padding: spacing.md,
  borderBottomWidth: 1,
  borderBottomColor: "rgba(255,255,255,0.1)",
}

const $conversationList: ViewStyle = {
  maxHeight: 300,
}

const $conversationItem: ThemedStyle<ViewStyle> = ({ colors }) => ({
  paddingHorizontal: spacing.md,
  paddingVertical: spacing.sm,
  borderBottomWidth: 1,
  borderBottomColor: colors.palette.overlay20,
})

const $conversationItemSelected: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.palette.overlay20,
})

const $selectedText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.tint,
})

const $conversationMeta: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.textDim,
  marginTop: spacing.xxxs,
})

const $loadingContainer: ViewStyle = {
  padding: spacing.lg,
  alignItems: "center",
}

const $newConversationButton: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "center",
  gap: spacing.xs,
  padding: spacing.md,
  borderTopWidth: 1,
  borderTopColor: colors.palette.overlay20,
})

const $newConversationText: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.tint,
})
