import { Button } from "@/components/Button"
import { Icon } from "@/components/Icon"
import { FoldersModal } from "@/components/custom/modals/folders-modal"
import { useGetAgentFolders } from "@/hooks/use-get-folders"
import { spacing } from "@/theme"
import { FC, useState } from "react"
import { View, ViewStyle } from "react-native"

interface AgentFoldersProps {
  agentId: string
}

export const AgentFolders: FC<AgentFoldersProps> = ({ agentId }) => {
  const { data: agentFolders } = useGetAgentFolders(agentId)
  const [showModal, setShowModal] = useState(false)

  return (
    <View style={$container}>
      <Button
        onPress={() => setShowModal(true)}
        text={`${agentFolders?.length || 0} attached folders`}
        RightAccessory={() => <Icon icon="Plus" size={20} />}
      />
      <FoldersModal visible={showModal} onDismiss={() => setShowModal(false)} />
    </View>
  )
}

const $container: ViewStyle = {
  gap: spacing.xs,
}
