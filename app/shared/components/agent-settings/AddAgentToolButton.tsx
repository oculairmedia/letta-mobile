import { Button } from "@/components/Button"
import { ComposioToolsModal } from "@/components/custom/modals/composio-tools-modal"
import { CustomToolsModal } from "@/components/custom/modals/custom-tools-modal"
import { MCPToolsModal } from "@/components/custom/modals/mcp-tools-modal"
import { Icon } from "@/components/Icon"
import { spacing } from "@/theme"
import { FC, useState } from "react"
import { View, ViewStyle } from "react-native"

interface AddAgentToolButtonProps {
  style?: ViewStyle
}

export const AddAgentToolButton: FC<AddAgentToolButtonProps> = ({ style }) => {
  const [showMCPModal, setShowMCPModal] = useState(false)
  const [showCustomModal, setShowCustomModal] = useState(false)
  const [showComposioModal, setShowComposioModal] = useState(false)

  return (
    <View style={[$container, style]}>
      <View style={$buttonContainer}>
        <Button
          LeftAccessory={() => <Icon icon="Plus" size={16} />}
          onPress={() => setShowMCPModal(true)}
          text="MCP"
          style={$button}
        />
        <Button
          LeftAccessory={() => <Icon icon="Plus" size={16} />}
          onPress={() => setShowCustomModal(true)}
          text="Custom"
          style={$button}
        />
        <Button
          LeftAccessory={() => <Icon icon="Plus" size={16} />}
          onPress={() => setShowComposioModal(true)}
          text="Composio"
          style={$button}
        />
      </View>

      <MCPToolsModal visible={showMCPModal} onDismiss={() => setShowMCPModal(false)} />
      <CustomToolsModal visible={showCustomModal} onDismiss={() => setShowCustomModal(false)} />
      <ComposioToolsModal
        visible={showComposioModal}
        onDismiss={() => setShowComposioModal(false)}
      />
    </View>
  )
}

const $container: ViewStyle = {
  gap: spacing.sm,
}

const $button: ViewStyle = {}

const $buttonContainer: ViewStyle = {
  flexDirection: "row",
  gap: spacing.sm,
  alignItems: "center",
  justifyContent: "space-between",
  flexWrap: "wrap",
}
