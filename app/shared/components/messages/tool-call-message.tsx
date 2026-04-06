import { Text } from "@/components"
import { normalizeName } from "@/shared/utils/normalizers"
import { spacing, Theme } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { CheckCircle2, Wrench, XCircle } from "lucide-react-native"
import { useMemo, useState } from "react"
import { TextStyle, View, ViewStyle } from "react-native"
import { BareAccordion } from "../animated/BareAccordion"
import { MemoizedCodeEditor } from "../letta-code-editor"

interface ToolCallMessageProps {
  content?: string
  style?: ViewStyle
  toolName?: string
  toolReturn?: string
  stdout?: string[]
  stderr?: string[]
  status?: "success" | "error"
}

function formatJSON(jsonString: string | undefined): string {
  if (!jsonString) return ""
  try {
    const parsed = JSON.parse(jsonString)
    return JSON.stringify(parsed, null, 2)
  } catch {
    return jsonString
  }
}

type MainTab = "input" | "output"
type OutputTab = "tool" | "stdout" | "stderr"

export function ToolCallMessage({
  content,
  style,
  toolName,
  toolReturn,
  stdout,
  stderr,
  status,
}: ToolCallMessageProps) {
  const [isExpanded, setIsExpanded] = useState(false)
  const [activeMainTab, setActiveMainTab] = useState<MainTab>("input")
  const [activeOutputTab, setActiveOutputTab] = useState<OutputTab>("tool")
  const { themed } = useAppTheme()

  const iconColor = useMemo(() => {
    if (!status) return themed($toolIcon).color
    return status === "success" ? themed($toolIcon).color : themed($errorIcon).color
  }, [status, themed])

  const { formattedInput, formattedOutput } = useMemo(() => {
    if (!content) return { formattedInput: "", formattedOutput: "" }
    return {
      formattedInput: formatJSON(content),
      formattedOutput: formatJSON(toolReturn),
    }
  }, [content, toolReturn])

  const StatusIcon = useMemo(() => {
    if (!status) return Wrench
    return status === "success" ? CheckCircle2 : XCircle
  }, [status])

  if (!content) return null

  return (
    <View style={[$container, style]}>
      <BareAccordion
        isExpanded={isExpanded}
        onToggle={() => setIsExpanded(!isExpanded)}
        style={themed($accordion)}
        touchableOpacityProps={{
          activeOpacity: 0.8,
        }}
        triggerNode={({ animatedChevron }) => (
          <View style={$toolCallFooter}>
            <View style={$toolCallHeader}>
              <View style={$toolInfoInner}>
                <StatusIcon size={16} color={iconColor} style={$toolIconBase} />
                <View style={$toolInfoContainer}>
                  <Text text={normalizeName(toolName)} style={themed($toolName)} />
                </View>
              </View>
              {animatedChevron}
            </View>
          </View>
        )}
      >
        <View style={$contentContainer}>
          {/* Main Tabs */}
          <View style={$tabButtons}>
            <Text
              text="Input"
              style={[themed($tabButton), activeMainTab === "input" && themed($activeTabButton)]}
              onPress={() => setActiveMainTab("input")}
            />
            <Text
              text="Output"
              style={[themed($tabButton), activeMainTab === "output" && themed($activeTabButton)]}
              onPress={() => setActiveMainTab("output")}
            />
          </View>

          {/* Input Tab Content */}
          {activeMainTab === "input" && (
            <View style={$section}>
              <View style={$codeContainer}>
                <MemoizedCodeEditor content={formattedInput} />
              </View>
            </View>
          )}

          {/* Output Tab Content */}
          {activeMainTab === "output" && (
            <View style={$section}>
              {/* Output Sub-tabs */}
              <View style={$tabButtons}>
                <Text
                  text="Tool Output"
                  style={[
                    themed($tabButton),
                    activeOutputTab === "tool" && themed($activeTabButton),
                  ]}
                  onPress={() => setActiveOutputTab("tool")}
                />
                <Text
                  text="stdout"
                  style={[
                    themed($tabButton),
                    activeOutputTab === "stdout" && themed($activeTabButton),
                  ]}
                  onPress={() => setActiveOutputTab("stdout")}
                />
                <Text
                  text="stderr"
                  style={[
                    themed($tabButton),
                    activeOutputTab === "stderr" && themed($activeTabButton),
                  ]}
                  onPress={() => setActiveOutputTab("stderr")}
                />
              </View>

              <View style={$codeContainer}>
                {activeOutputTab === "tool" && (
                  <MemoizedCodeEditor key="tool" content={formattedOutput} />
                )}
                {activeOutputTab === "stdout" && (
                  <MemoizedCodeEditor key="stdout" content={stdout?.join("\n") || "None"} />
                )}
                {activeOutputTab === "stderr" && (
                  <MemoizedCodeEditor key="stderr" content={stderr?.join("\n") || "None"} />
                )}
              </View>
            </View>
          )}
        </View>
      </BareAccordion>
    </View>
  )
}

const $container: ViewStyle = {
  flexDirection: "column",
  gap: spacing.xs,
}

const $toolCallHeader: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "space-between",
  gap: spacing.xs,
  flex: 1,
}

const $toolInfoInner: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  gap: spacing.xs,
}

const $toolInfoContainer: ViewStyle = {
  flexDirection: "column",
  alignItems: "flex-start",
  gap: spacing.xs,
}

const $toolIconBase: ViewStyle = {}

const $toolIcon = ({ colors }: { colors: Theme["colors"] }) => ({
  color: colors.palette.success500,
})

const $errorIcon = ({ colors }: { colors: Theme["colors"] }) => ({
  color: colors.error,
})

const $accordion = ({ colors }: Theme): ViewStyle => ({
  flex: 1,
  borderColor: colors.elementColors.button.default.borderColor,
  borderWidth: 1,
})

const $toolName = ({ colors }: Theme): TextStyle => ({
  color: colors.text,
})

const $contentContainer: ViewStyle = {}

const $section: ViewStyle = {
  gap: spacing.xs,
}

const $tabButtons: ViewStyle = {
  flexDirection: "row",
  marginBottom: spacing.xs,
}

const $tabButton = ({ colors }: { colors: Theme["colors"] }): TextStyle => ({
  color: colors.text,
  fontSize: 12,
  paddingVertical: spacing.xs,
  paddingHorizontal: spacing.sm,
  backgroundColor: colors.elementColors.button.default.backgroundColor,
  borderRadius: 4,
})

const $activeTabButton = ({ colors }: Theme): TextStyle => ({
  backgroundColor: colors.text,
  color: colors.elementColors.button.reversed.textColor,
})

const $codeContainer: ViewStyle = {
  borderRadius: 4,
  overflow: "hidden",
  flex: 1,
}

const $toolCallFooter: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "space-between",
  padding: spacing.sm,
}
