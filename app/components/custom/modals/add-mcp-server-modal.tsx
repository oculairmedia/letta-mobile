import { Screen } from "@/components"
import { MCPServerForm } from "@/components/custom/forms/mcp-server-form"
import { ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Letta } from "@letta-ai/letta-client"
import type { FC } from "react"
import { Modal, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"

interface AddMCPServerModalProps {
  visible: boolean
  onDismiss: () => void
  onSubmit: (serverData: Letta.SseServerConfig | Letta.StdioServerConfig) => void
  isPending?: boolean
}

export const AddMCPServerModal: FC<AddMCPServerModalProps> = ({
  visible,
  onDismiss,
  onSubmit,
  isPending,
}) => {
  const { themed } = useAppTheme()
  const { bottom } = useSafeAreaInsets()

  return (
    <Modal
      visible={visible}
      onRequestClose={onDismiss}
      animationType="slide"
      presentationStyle="pageSheet"
    >
      <Screen
        preset="scroll"
        style={themed($modalContainer)}
        contentContainerStyle={[themed($contentContainer), { paddingBottom: bottom }]}
      >
        <MCPServerForm onSubmit={onSubmit} onCancel={onDismiss} isPending={isPending} />
      </Screen>
    </Modal>
  )
}

const $modalContainer: ThemedStyle<ViewStyle> = ({ colors }) => ({
  flex: 1,
  backgroundColor: colors.background,
})

const $contentContainer: ThemedStyle<ViewStyle> = ({ spacing }) => ({
  padding: spacing.md,
})
