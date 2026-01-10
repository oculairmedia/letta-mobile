import { Button } from "@/components/Button"
import { Icon } from "@/components/Icon"
import { useAgentId } from "@/hooks/use-agentId-param"
import { useAttachToolToAgent } from "@/hooks/use-letta-tools"

export const AttachMCPToolAction = ({
  tool,
  onSuccess,
}: {
  tool: { id?: string; name: string }
  onSuccess?: () => void
}) => {
  const [agentId] = useAgentId()
  const { mutate: attachTool, isPending: isAttachingTool } = useAttachToolToAgent({
    onSuccess,
  })

  return (
    <Button
      preset="icon"
      onPress={() => {
        if (tool.id) {
          attachTool({ agentId, toolId: tool.id })
        }
      }}
      loading={isAttachingTool}
      RightAccessory={() => <Icon icon="Plus" />}
    />
  )
}
