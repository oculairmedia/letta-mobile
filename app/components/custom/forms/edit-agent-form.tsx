import { Text, TextField } from "@/components"
import { Switch } from "@/components/Toggle/Switch"
import { useAgent } from "@/hooks/use-agent"
import type { ThemedStyle } from "@/theme"
import { colors, spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { UpdateAgent } from "@letta-ai/letta-client/api"
import { Eraser, Undo2 } from "lucide-react-native"
import { Fragment, useEffect, useMemo, useState } from "react"
import type { TextStyle, ViewStyle } from "react-native"
import { TouchableOpacity, View } from "react-native"
import { useLettaHeader } from "../useLettaHeader"

interface EditAgentFormProps {
  agentId: string
  onSubmit?: (agentData: UpdateAgent & { id: string }) => void
  isPending?: boolean
}

interface FieldActionsProps {
  onReset: () => void
  onClear: () => void
  isModified: boolean
}

function FieldActions({ onReset, onClear, isModified }: FieldActionsProps) {
  if (!isModified) return null

  return (
    <View style={$actions}>
      <TouchableOpacity onPress={onReset} style={$actionButton}>
        <Undo2 size={16} color={colors.palette.primary200} />
      </TouchableOpacity>
      <TouchableOpacity onPress={onClear} style={$actionButton}>
        <Eraser size={16} color={colors.palette.angry500} />
      </TouchableOpacity>
    </View>
  )
}

export const EditAgentForm = function EditAgentForm({
  agentId,
  onSubmit,
  isPending,
}: EditAgentFormProps) {
  const { themed, theme } = useAppTheme()
  const { data: agent } = useAgent(agentId)

  // Basic Information
  const [name, setName] = useState("")
  const [description, setDescription] = useState("")
  const [tags, setTags] = useState("")

  // Tool Settings
  const [messageBufferAutoclear, setMessageBufferAutoclear] = useState(false)

  const initialFormValues = useMemo(() => {
    if (!agent) return null
    return {
      name: agent?.name || "",
      description: agent?.description || "",
      tags: agent?.tags.join(", ") || "",
      messageBufferAutoclear: agent?.messageBufferAutoclear ?? false,
    }
  }, [agent])

  // Initialize form with agent data
  useEffect(() => {
    if (initialFormValues) {
      setName(initialFormValues.name)
      setDescription(initialFormValues.description)
      setTags(initialFormValues.tags)
      setMessageBufferAutoclear(initialFormValues.messageBufferAutoclear)
    }
  }, [initialFormValues])

  const originalValues = useMemo(
    () => ({
      name: agent?.name || "",
      description: agent?.description || "",
      tags: agent?.tags.join(", ") || "",
    }),
    [agent],
  )

  const handleSubmit = () => {
    const agentData: UpdateAgent & { id: string } = {
      id: agentId,
      name,
      description,
      tags: tags
        .split(",")
        .map((tag) => tag.trim())
        .filter(Boolean),
      messageBufferAutoclear,
    }

    onSubmit?.(agentData)
  }

  const isDisabled = useMemo(() => isPending || !name, [isPending, name])

  useLettaHeader(
    {
      onRightPress: isDisabled ? undefined : handleSubmit,
      rightIcon: "Save",
      rightIconColor: isDisabled ? theme.colors.tintInactive : theme.colors.tint,
    },
    [isDisabled],
  )

  if (!agent) {
    return null
  }

  return (
    <Fragment>
      <Text text="Basic Information" preset="heading" style={themed($sectionTitleText)} />

      <View style={$fieldContainer}>
        <TextField
          value={name}
          onChangeText={setName}
          containerStyle={themed($textField)}
          label="Agent Name"
          placeholder="Enter agent name"
          helper={!name ? "Name is required" : undefined}
          status={!name ? "error" : undefined}
        />
        <FieldActions
          isModified={name !== originalValues.name}
          onReset={() => setName(originalValues.name)}
          onClear={() => setName("")}
        />
      </View>

      <View style={$fieldContainer}>
        <TextField
          value={description}
          onChangeText={setDescription}
          containerStyle={themed($textField)}
          label="Description"
          placeholder="Enter agent description"
          multiline
          numberOfLines={3}
        />
        <FieldActions
          isModified={description !== originalValues.description}
          onReset={() => setDescription(originalValues.description)}
          onClear={() => setDescription("")}
        />
      </View>

      <Text
        text="Generate Human and Persona"
        preset="formHelper"
        style={themed($sectionTitleText)}
      />

      <View style={$fieldContainer}>
        <TextField
          value={tags}
          onChangeText={setTags}
          containerStyle={themed($textField)}
          label="Tags"
          placeholder="Enter tags, separated by commas"
        />
        <FieldActions
          isModified={tags !== originalValues.tags}
          onReset={() => setTags(originalValues.tags)}
          onClear={() => setTags("")}
        />
      </View>

      <View style={themed($toggle)}>
        <Text text="Message Buffer Autoclear" />
        <Switch value={messageBufferAutoclear} onValueChange={setMessageBufferAutoclear} />
      </View>
    </Fragment>
  )
}

const $actions: ViewStyle = {
  flexDirection: "row",
  gap: spacing.xs,
  paddingLeft: spacing.xs,
}

const $actionButton: ViewStyle = {
  padding: spacing.xs,
}

const $fieldContainer: ViewStyle = {
  flexDirection: "row",
  alignItems: "flex-start",
  marginBottom: spacing.sm,
}

const $sectionTitleText: ThemedStyle<TextStyle> = () => ({
  marginTop: spacing.lg,
  marginBottom: spacing.sm,
})

const $textField: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
})

const $toggle: ThemedStyle<ViewStyle> = () => ({
  flexDirection: "row",
  justifyContent: "space-between",
  alignItems: "center",
  marginVertical: spacing.xs,
})
