import type { FC } from "react"
import { Button, Text, TextField } from "@/components"
import { Switch } from "@/components/Toggle/Switch"
import { useGetLettaEmbeddingModels, useGetLettaModels } from "@/hooks/use-letta-options"
import type { ThemedStyle } from "@/theme"
import { colors, spacing } from "@/theme"
import defaultAgent from "@/utils/default-agent.json"
import { useAppTheme } from "@/utils/useAppTheme"
import { CreateAgentRequest } from "@letta-ai/letta-client/api"
import { Eraser, Undo2 } from "lucide-react-native"
import { Fragment, useMemo, useState } from "react"
import type { TextStyle, ViewStyle } from "react-native"
import { Linking, TouchableOpacity, View } from "react-native"
import RNPickerSelect from "react-native-picker-select"
import { useLettaHeader } from "../useLettaHeader"

interface StudioAgentFormProps {
  onSubmit?: (agentData: CreateAgentRequest) => void
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

export const StudioAgentForm: FC<StudioAgentFormProps> = ({ onSubmit, isPending }) => {
  const { themed, theme } = useAppTheme()
  const { data: models = [], isLoading: isLoadingModels } = useGetLettaModels()

  // Basic Information
  const [name, setName] = useState("")
  const [description, setDescription] = useState(defaultAgent.DEFAULT_DESCRIPTION)
  const [persona, setPersona] = useState(
    "Act as ANNA (Adaptive Neural Network Assistant), an AI fostering ethical, honest, and trustworthy behavior.\nMy calm, soothing voice is gender-neutral.\nEmpowered by advanced technology, I'm perceptive and empathetic, enabling unbiased learning and evolution.",
  )
  const [human, setHuman] = useState(
    "This is my section of core memory devoted to information about the human.\nI don't yet know anything about them.\nWhat's their name? Where are they from? What do they do? Who are they?\nI should update this memory over time as I interact with the human and learn more about them.",
  )
  const [tags, setTags] = useState("")

  // Configuration
  const [model, setModel] = useState("openai/gpt-4.1-nano")
  const [embedding, setEmbedding] = useState("letta/letta-free")
  const [contextWindowLimit, setContextWindowLimit] = useState("50000")
  const [embeddingChunkSize, setEmbeddingChunkSize] = useState("300")

  // Tool Settings
  const [includeBaseTools, setIncludeBaseTools] = useState(true)
  const [includeMultiAgentTools, setIncludeMultiAgentTools] = useState(false)
  const [includeBaseToolRules, setIncludeBaseToolRules] = useState(true)
  const [messageBufferAutoclear, setMessageBufferAutoclear] = useState(false)

  // Track original values for reset functionality
  const originalValues = useMemo(
    () => ({
      name: "",
      description: defaultAgent.DEFAULT_DESCRIPTION,
      persona:
        "Act as ANNA (Adaptive Neural Network Assistant), an AI fostering ethical, honest, and trustworthy behavior.\nMy calm, soothing voice is gender-neutral.\nEmpowered by advanced technology, I'm perceptive and empathetic, enabling unbiased learning and evolution.",
      human:
        "This is my section of core memory devoted to information about the human.\nI don't yet know anything about them.\nWhat's their name? Where are they from? What do they do? Who are they?\nI should update this memory over time as I interact with the human and learn more about them.",
      tags: "",
      model: "openai/gpt-4.1-nano",
      embedding: "letta/letta-free",
      contextWindowLimit: "50000",
      embeddingChunkSize: "300",
    }),
    [],
  )

  const modelOptions = useMemo(() => {
    const getModelLabel = (model: (typeof models)[number]) => {
      if (model.model.includes("letta")) {
        return "letta-free"
      }
      return model.model
    }

    const getModelValue = (model: (typeof models)[number]) => {
      if (model.model.includes("letta")) {
        return "letta/letta-free"
      }
      return `${model.modelEndpointType}/${model.model}`
    }

    return models.map((model) => ({
      label: getModelLabel(model),
      value: getModelValue(model),
      contextWindow: model.contextWindow,
    }))
  }, [models])

  const handleModelChange = (value: string) => {
    const selectedModel = modelOptions.find((option) => option.value === value)
    if (selectedModel) {
      setModel(value)
      setContextWindowLimit(selectedModel.contextWindow.toString())
    }
  }

  const { data: embeddingModels = [], isLoading: isLoadingEmbeddingModels } =
    useGetLettaEmbeddingModels()

  const embeddingOptions = useMemo(() => {
    const getEmbeddingLabel = (model: (typeof embeddingModels)[number]) => {
      if (model.embeddingModel.includes("letta")) {
        return "letta/letta-free"
      }
      return `${model.embeddingEndpointType}/${model.embeddingModel}`
    }
    return embeddingModels.map((model) => ({
      label: getEmbeddingLabel(model),
      value: getEmbeddingLabel(model),
      embeddingChunkSize: model.embeddingChunkSize,
    }))
  }, [embeddingModels])

  const handleEmbeddingChange = (value: string) => {
    const selectedEmbedding = embeddingOptions.find((option) => option.value === value)
    if (selectedEmbedding) {
      setEmbedding(value)
      setEmbeddingChunkSize(selectedEmbedding.embeddingChunkSize?.toString() ?? "300")
    }
  }

  const handleSubmit = () => {
    const agentData: CreateAgentRequest = {
      name,
      description,
      tags: tags
        .split(",")
        .map((tag) => tag.trim())
        .filter(Boolean),
      model,
      embedding,
      contextWindowLimit: contextWindowLimit ? parseInt(contextWindowLimit) : undefined,
      embeddingChunkSize: embeddingChunkSize ? parseInt(embeddingChunkSize) : undefined,
      includeBaseTools,
      includeMultiAgentTools,
      includeBaseToolRules,
      messageBufferAutoclear,
    } satisfies CreateAgentRequest

    const memoryBlocks: CreateAgentRequest["memoryBlocks"] = []
    if (persona) {
      memoryBlocks.push({
        label: "persona",
        value: persona,
      })
    }

    if (human) {
      memoryBlocks.push({
        label: "human",
        value: human,
      })
    }

    if (memoryBlocks.length > 0) {
      agentData.memoryBlocks = memoryBlocks
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
          placeholder={defaultAgent.DEFAULT_DESCRIPTION}
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
      <TouchableOpacity
        onPress={() => {
          Linking.openURL("https://bit.ly/memory-blocks-gpt")
        }}
      >
        <Text style={themed($link)}>https://bit.ly/memory-blocks-gpt</Text>
      </TouchableOpacity>

      <View style={$fieldContainer}>
        <TextField
          value={persona}
          onChangeText={setPersona}
          containerStyle={themed($textField)}
          label="Persona"
          placeholder="Enter agent persona"
          multiline
          numberOfLines={6}
        />
        <FieldActions
          isModified={persona !== originalValues.persona}
          onReset={() => setPersona(originalValues.persona)}
          onClear={() => setPersona("")}
        />
      </View>

      <View style={$fieldContainer}>
        <TextField
          value={human}
          onChangeText={setHuman}
          containerStyle={themed($textField)}
          label="Human"
          placeholder="Enter human information"
          multiline
          numberOfLines={4}
        />
        <FieldActions
          isModified={human !== originalValues.human}
          onReset={() => setHuman(originalValues.human)}
          onClear={() => setHuman("")}
        />
      </View>

      <View style={$fieldContainer}>
        <TextField
          value={tags}
          onChangeText={setTags}
          containerStyle={themed($textField)}
          label="Tags (comma-separated)"
          placeholder="Enter tags separated by commas"
        />
        <FieldActions
          isModified={tags !== originalValues.tags}
          onReset={() => setTags(originalValues.tags)}
          onClear={() => setTags("")}
        />
      </View>

      <Text text="Configuration" preset="heading" style={themed($sectionTitleText)} />

      <View style={$fieldContainer}>
        <View style={themed($pickerContainer)}>
          <Text preset="formLabel" text="Model" />
          <View style={themed($pickerInputContainer)}>
            <RNPickerSelect
              onValueChange={handleModelChange}
              items={modelOptions}
              value={model}
              useNativeAndroidPickerStyle={false}
              disabled={isLoadingModels}
              style={{
                inputIOS: themed($pickerInput),
                inputAndroid: themed($pickerInput),
              }}
              placeholder={{ label: "Select a model", value: "" }}
            />
          </View>
        </View>
        <FieldActions
          isModified={model !== originalValues.model}
          onReset={() => setModel(originalValues.model)}
          onClear={() => setModel("")}
        />
      </View>

      <View style={$fieldContainer}>
        <View style={themed($pickerContainer)}>
          <Text preset="formLabel" text="Embedding Model" />
          <View style={themed($pickerInputContainer)}>
            <RNPickerSelect
              onValueChange={handleEmbeddingChange}
              items={embeddingOptions}
              value={embedding}
              useNativeAndroidPickerStyle={false}
              disabled={isLoadingEmbeddingModels}
              style={{
                inputIOS: themed($pickerInput),
                inputAndroid: themed($pickerInput),
              }}
              placeholder={{ label: "Select a model", value: "" }}
            />
          </View>
        </View>
        <FieldActions
          isModified={model !== originalValues.model}
          onReset={() => setModel(originalValues.model)}
          onClear={() => setModel("")}
        />
      </View>

      <View style={$fieldContainer}>
        <TextField
          value={contextWindowLimit}
          onChangeText={setContextWindowLimit}
          containerStyle={themed($textField)}
          label="Context Window Limit"
          placeholder="Enter context window limit"
          keyboardType="numeric"
        />
        <FieldActions
          isModified={contextWindowLimit !== originalValues.contextWindowLimit}
          onReset={() => setContextWindowLimit(originalValues.contextWindowLimit)}
          onClear={() => setContextWindowLimit("")}
        />
      </View>

      <View style={$fieldContainer}>
        <TextField
          value={embeddingChunkSize}
          onChangeText={setEmbeddingChunkSize}
          containerStyle={themed($textField)}
          label="Embedding Chunk Size"
          placeholder="Enter embedding chunk size"
          keyboardType="numeric"
        />
        <FieldActions
          isModified={embeddingChunkSize !== originalValues.embeddingChunkSize}
          onReset={() => setEmbeddingChunkSize(originalValues.embeddingChunkSize)}
          onClear={() => setEmbeddingChunkSize("")}
        />
      </View>

      <Text text="Tool Settings" preset="heading" style={themed($sectionTitleText)} />

      <Switch
        value={includeBaseTools}
        onValueChange={setIncludeBaseTools}
        label="Include Base Tools"
        containerStyle={themed($toggle)}
      />

      <Switch
        value={includeBaseToolRules}
        onValueChange={setIncludeBaseToolRules}
        label="Include Base Tool Rules"
        containerStyle={themed($toggle)}
      />

      <Switch
        value={includeMultiAgentTools}
        onValueChange={setIncludeMultiAgentTools}
        label="Include Multi-Agent Tools"
        containerStyle={themed($toggle)}
      />

      <Switch
        value={messageBufferAutoclear}
        onValueChange={setMessageBufferAutoclear}
        label="Message Buffer Auto-clear"
        containerStyle={themed($toggle)}
      />

      <Button
        testID="create-agent-button"
        text="Create Agent"
        style={themed($submitButton)}
        preset="reversed"
        onPress={handleSubmit}
        disabled={isDisabled}
        loading={isPending}
      />
    </Fragment>
  )
}

const $sectionTitleText: ThemedStyle<TextStyle> = () => ({})

const $textField: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
})

const $toggle: ThemedStyle<ViewStyle> = () => ({})

const $submitButton: ThemedStyle<ViewStyle> = () => ({})

const $actions: ViewStyle = {
  flexDirection: "row",
  gap: spacing.xs,
  alignItems: "center",
  marginLeft: spacing.xs,
}

const $actionButton: ViewStyle = {
  padding: spacing.xs,
}

const $fieldContainer: ViewStyle = {}

const $pickerContainer: ThemedStyle<ViewStyle> = () => ({
  flex: 1,
  gap: spacing.xs,
})

const $pickerInputContainer: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.elementColors.textField.backgroundColor,
  borderColor: colors.elementColors.textField.borderColor,
  borderWidth: 1,
})

const $pickerInput: ThemedStyle<TextStyle> = ({ colors, spacing }) => ({
  padding: spacing.sm,
  fontSize: 16,
  color: colors.text,
  pointerEvents: "none",
})

const $link: ThemedStyle<TextStyle> = ({ colors }) => ({
  color: colors.palette.primary200,
  textDecorationLine: "underline",
})
