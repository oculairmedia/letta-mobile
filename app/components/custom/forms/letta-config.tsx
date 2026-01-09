import { Button, Text, TextField } from "@/components"
import { Switch } from "@/components/Toggle/Switch"
import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import { spacing } from "@/theme"
import { FC, useEffect, useState } from "react"
import { View, ViewStyle } from "react-native"

interface LettaConfigsFormProps {
  onSubmit: (config: {
    id?: string
    mode: "cloud" | "selfhosted"
    serverUrl?: string
    accessToken: string
  }) => void
  isPending?: boolean
  initialValues?: {
    id: string
    mode: "cloud" | "selfhosted"
    serverUrl: string
    accessToken: string
  }
}

export const LettaConfigsForm: FC<LettaConfigsFormProps> = ({
  onSubmit,
  isPending,
  initialValues,
}) => {
  const { deleteConfig } = useLettaConfigStore()
  const [mode, setMode] = useState<"cloud" | "selfhosted">(initialValues?.mode || "cloud")
  const [serverUrl, setServerUrl] = useState(initialValues?.serverUrl || "")
  const [accessToken, setAccessToken] = useState(initialValues?.accessToken || "")

  useEffect(() => {
    if (initialValues) {
      setMode(initialValues.mode)
      setServerUrl(initialValues.serverUrl)
      setAccessToken(initialValues.accessToken)
    }
  }, [initialValues])

  const handleSubmit = () => {
    onSubmit({
      id: initialValues?.id,
      mode,
      serverUrl: mode === "selfhosted" ? serverUrl : undefined,
      accessToken,
    })
  }

  const handleDelete = () => {
    if (initialValues?.id) {
      deleteConfig(initialValues.id)
    }
  }

  return (
    <View style={$formContainer}>
      <Text text="Connection Mode" preset="heading" />
      <View style={$switchContainer}>
        <Switch
          value={mode === "selfhosted"}
          onValueChange={(value) => setMode(value ? "selfhosted" : "cloud")}
          label={mode === "selfhosted" ? "Self-hosted" : "Cloud"}
        />
      </View>
      <Text
        text={
          mode === "cloud"
            ? "Connect to Letta's cloud service"
            : "Connect to your self-hosted Letta instance"
        }
      />

      {mode === "selfhosted" && (
        <View style={$fieldContainer}>
          <TextField
            value={serverUrl}
            onChangeText={setServerUrl}
            label="Server URL"
            placeholder="https://your-letta-server.com"
            autoCapitalize="none"
            autoCorrect={false}
          />
          <Text text="Enter the URL of your Letta server" />
        </View>
      )}

      <View style={$fieldContainer}>
        <TextField
          value={accessToken}
          onChangeText={setAccessToken}
          label="Access Token"
          placeholder="Enter your access token"
          autoCapitalize="none"
          autoCorrect={false}
          secureTextEntry
        />
        <Text text="Your Letta access token" />
      </View>

      <View style={$buttonContainer}>
        {initialValues && (
          <Button style={$button} preset="destructive" onPress={handleDelete} text="Delete" />
        )}
        <Button
          style={$button}
          preset="filled"
          onPress={handleSubmit}
          disabled={isPending}
          text={initialValues ? "Save" : "Add"}
        />
      </View>
    </View>
  )
}

const $formContainer: ViewStyle = {
  flex: 1,
}

const $switchContainer: ViewStyle = {
  flexDirection: "row",
  alignItems: "center",
  marginVertical: spacing.sm,
}

const $fieldContainer: ViewStyle = {
  marginVertical: spacing.sm,
}

const $buttonContainer: ViewStyle = {
  flexDirection: "row",
  gap: spacing.sm,
}

const $button: ViewStyle = {
  flex: 1,
}
