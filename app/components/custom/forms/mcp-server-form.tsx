import { Button, Text, TextField } from "@/components"
import { Switch } from "@/components/Toggle/Switch"
import type { ThemedStyle } from "@/theme"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { Letta } from "@letta-ai/letta-client"
import { FC, Fragment, useMemo, useState } from "react"
import type { TextStyle, ViewStyle } from "react-native"
import { View } from "react-native"

interface MCPServerFormProps {
  onSubmit?: (serverData: Letta.SseServerConfig | Letta.StdioServerConfig) => void
  onCancel?: () => void
  isPending?: boolean
}
export const MCPServerForm: FC<MCPServerFormProps> = ({ onSubmit, onCancel, isPending }) => {
  const { themed } = useAppTheme()

  // Basic Information
  const [serverName, setServerName] = useState("")
  const [isSSE, setIsSSE] = useState(false)

  // SSE Configuration
  const [serverUrl, setServerUrl] = useState("")

  // Stdio Configuration
  const [command, setCommand] = useState("")
  const [args, setArgs] = useState("")
  const [env, setEnv] = useState("")

  const handleSubmit = () => {
    if (!serverName.trim()) return

    if (isSSE) {
      if (!serverUrl.trim()) return

      onSubmit?.({
        serverName: serverName.trim(),
        type: "sse",
        serverUrl: serverUrl.trim(),
      })
    } else {
      if (!command.trim()) return

      onSubmit?.({
        serverName: serverName.trim(),
        type: "stdio",
        command: command.trim(),
        args: args
          .split(",")
          .map((arg) => arg.trim())
          .filter(Boolean),
        ...(env.trim() && {
          env: env
            .split(",")
            .map((e) => e.trim())
            .filter(Boolean)
            .reduce(
              (acc, curr) => {
                const [key, value] = curr.split("=").map((s) => s.trim())
                if (key && value) {
                  acc[key] = value
                }
                return acc
              },
              {} as Record<string, string | undefined>,
            ),
        }),
      })
    }
  }

  const isDisabled = useMemo(() => {
    if (isPending || !serverName.trim()) return true
    if (isSSE && !serverUrl.trim()) return true
    if (!isSSE && !command.trim()) return true
    return false
  }, [isPending, serverName, isSSE, serverUrl, command])

  return (
    <Fragment>
      <Text text="Server Type" preset="heading" style={themed($sectionTitleText)} />

      <View style={$switchContainer}>
        <Switch
          value={isSSE}
          onValueChange={setIsSSE}
          label={isSSE ? "SSE Server" : "Stdio Server"}
          containerStyle={themed($toggle)}
        />
        <Button
          text="Clear"
          onPress={() => {
            setServerName("")
            setServerUrl("")
            setCommand("")
            setArgs("")
            setEnv("")
          }}
          style={themed($clearButton)}
        />
      </View>

      <Text text="Basic Information" preset="heading" style={themed($sectionTitleText)} />

      <View style={$fieldContainer}>
        <TextField
          value={serverName}
          onChangeText={setServerName}
          containerStyle={themed($textField)}
          label="Server Name"
          placeholder="Enter server name"
          helper="Names must be unique to your Letta instance"
          status={!serverName.trim() ? "error" : undefined}
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      {isSSE ? (
        <Fragment>
          <Text text="SSE Configuration" preset="heading" style={themed($sectionTitleText)} />
          <View style={$fieldContainer}>
            <TextField
              value={serverUrl}
              onChangeText={setServerUrl}
              containerStyle={themed($textField)}
              label="Server URL"
              placeholder="http://localhost:5000"
              helper="The URL of the server"
              status={!serverUrl.trim() ? "error" : undefined}
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
        </Fragment>
      ) : (
        <Fragment>
          <Text text="Stdio Configuration" preset="heading" style={themed($sectionTitleText)} />
          <View style={$fieldContainer}>
            <TextField
              value={command}
              onChangeText={setCommand}
              containerStyle={themed($textField)}
              label="Command"
              placeholder="python3 -m mcp.server"
              helper="The command to start the server"
              status={!command.trim() ? "error" : undefined}
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>

          <View style={$fieldContainer}>
            <TextField
              value={args}
              onChangeText={setArgs}
              containerStyle={themed($textField)}
              label="Arguments"
              placeholder="-port 5000,-host"
              helper="Please provide arguments as a comma separated list"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>

          <View style={$fieldContainer}>
            <TextField
              value={env}
              onChangeText={setEnv}
              containerStyle={themed($textField)}
              label="Environment Variables"
              placeholder="KEY1=value1,KEY2=value2"
              helper="Please provide environment variables as a comma separated list of KEY=value pairs"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
        </Fragment>
      )}

      <View style={$buttonContainer}>
        <Button
          text="Cancel"
          style={themed($cancelButton)}
          onPress={onCancel}
          disabled={isPending}
        />
        <Button
          text="Add Server"
          style={themed($submitButton)}
          preset="reversed"
          onPress={handleSubmit}
          disabled={isDisabled}
          loading={isPending}
        />
      </View>
    </Fragment>
  )
}

const $sectionTitleText: ThemedStyle<TextStyle> = () => ({
  marginTop: spacing.md,
})

const $textField: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
})

const $toggle: ThemedStyle<ViewStyle> = () => ({
  marginTop: spacing.xs,
})

const $fieldContainer: ViewStyle = {
  marginTop: spacing.xs,
}

const $buttonContainer: ViewStyle = {
  flexDirection: "row",
  justifyContent: "space-between",
  gap: spacing.sm,
  marginTop: spacing.xl,
}

const $submitButton: ThemedStyle<ViewStyle> = () => ({
  minWidth: 120,
})

const $cancelButton: ThemedStyle<ViewStyle> = () => ({
  minWidth: 120,
})

const $switchContainer: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  justifyContent: "space-between",
  marginTop: spacing.xs,
}

const $clearButton: ThemedStyle<ViewStyle> = () => ({
  minWidth: 80,
})
