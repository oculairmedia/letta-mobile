import { Card, CardProps } from "@/components"
import { normalizeToolName } from "@/shared/utils/normalizers"
import { Tool } from "@letta-ai/letta-client/api"
import { ViewStyle } from "react-native"

interface ToolCardProps extends CardProps {
  tool: Tool
}
export const ToolCard = ({ tool, ...props }: ToolCardProps) => {
  return (
    <Card
      key={tool.id}
      heading={normalizeToolName(tool.name)}
      content={tool.description?.trim()}
      ContentTextProps={{ numberOfLines: 2 }}
      style={$toolCard}
      {...props}
    />
  )
}

const $toolCard: ViewStyle = {
  marginBottom: 0,
  minHeight: 0,
}
