import { Button, Text, TextField } from "@/components"
import { Switch } from "@/components/Toggle/Switch"
import type { ThemedStyle } from "@/theme"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { McpServerCreateParams } from "@letta-ai/letta-client/resources/index.mjs"
import { FC, Fragment, useMemo, useState } from "react"
import type { TextStyle, ViewStyle } from "react-native"
import { View } from "react-native"
import { useLettaClient } from "@/providers/LettaProvider"

export type MCPServerFormData = McpServerCreateParams

interface MCPServerFormProps {
  onSubmit?: (serverData: MCPServerFormData) => void
  onCancel?: () => void
  isPending?: boolean
}

export const MCPServerForm: FC<MCPServerFormProps> = ({ onSubmit, onCancel, isPending }) => {
  const { themed } = useAppTheme()
  const { lettaClient } = useLettaClient()

  // Detect if running in Cloud
  const isCloud = useMemo(() => {
    const baseUrl = lettaClient.baseURL || ""
    return baseUrl.includes("api.letta.com")
  }, [lettaClient.baseURL])

  // Basic Information
  const [serverName, setServerName] = useState("")
  const [isStdio, setIsStdio] = useState(false)

  // Streamable HTTP Configuration
  const [serverUrl, setServerUrl] = useState("")
  const [authHeader, setAuthHeader] = useState("")
  const [authToken, setAuthToken] = useState("")

  // Stdio Configuration
  const [command, setCommand] = useState("")
  const [args, setArgs] = useState("")
  const [env, setEnv] = useState("")

  const handleSubmit = () => {
    if (!serverName.trim()) return

    if (isStdio && !isCloud) {
      if (!command.trim()) return
      onSubmit?.({
        server_name: serverName.trim(),
        config: {
          mcp_server_type: "stdio",
          command: command.trim(),
          args: args
            .trim()
            .split(",")
            .map((arg) => arg.trim())
            .filter(Boolean),
          env: env
            .trim()
            .split(",")
            .map((e) => e.trim())
            .filter(Boolean)
            .reduce(
              (acc, curr) => {
                const [key, value] = curr.split("=").map((s) => s.trim())
                if (key && typeof value !== "undefined") {
                  acc[key] = value
                }
                return acc
              },
              {} as Record<string, string>,
            ),
        },
      })
    } else {
      if (!serverUrl.trim()) return
      onSubmit?.({
        server_name: serverName.trim(),
        config: {
          mcp_server_type: "streamable_http",
          server_url: serverUrl.trim(),
          auth_header: authHeader.trim() || undefined,
          auth_token: authToken.trim() || undefined,
        },
      })
    }
  }

  const isDisabled = useMemo(() => {
    if (isPending || !serverName.trim()) return true
    if (isStdio && !isCloud) {
      return !command.trim()
    } else {
      return !serverUrl.trim()
    }
  }, [isPending, serverName, isStdio, isCloud, serverUrl, command])

  return (
    <Fragment>
      {!isCloud && (
        <Fragment>
          <Text text="Server Type" preset="heading" style={themed($sectionTitleText)} />
          <View style={$switchContainer}>
            <Switch
              value={isStdio}
              onValueChange={setIsStdio}
              label={isStdio ? "Stdio (Local)" : "Streamable HTTP"}
              containerStyle={themed($toggle)}
            />
          </View>
        </Fragment>
      )}

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

      <Text text="Configuration" preset="heading" style={themed($sectionTitleText)} />

      {isStdio && !isCloud ? (
        <Fragment>
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
              helper="Comma-separated arguments"
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
              placeholder="KEY=value,KEY2=value2"
              helper="Comma-separated KEY=value pairs"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>
        </Fragment>
      ) : (
        <Fragment>
          <View style={$fieldContainer}>
            <TextField
              value={serverUrl}
              onChangeText={setServerUrl}
              containerStyle={themed($textField)}
              label="Server URL"
              placeholder="https://example.com/mcp"
              helper="The URL of the streamable HTTP MCP server"
              status={!serverUrl.trim() ? "error" : undefined}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
            />
          </View>

          <View style={$fieldContainer}>
            <TextField
              value={authHeader}
              onChangeText={setAuthHeader}
              containerStyle={themed($textField)}
              label="Auth Header (Optional)"
              placeholder="Authorization"
              helper="Header name for authentication"
              autoCapitalize="none"
              autoCorrect={false}
            />
          </View>

          <View style={$fieldContainer}>
            <TextField
              value={authToken}
              onChangeText={setAuthToken}
              containerStyle={themed($textField)}
              label="Auth Token (Optional)"
              placeholder="Bearer token..."
              helper="Token value for authentication"
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

const _$clearButton: ThemedStyle<ViewStyle> = () => ({
  minWidth: 80,
})
