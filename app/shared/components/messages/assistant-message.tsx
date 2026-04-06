import { AutoImage } from "@/components/AutoImage"
import { Card, CardProps } from "@/components/Card"
import { SimpleContextMenu } from "@/components/simple-context-menu"
import { spacing } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { memo, ReactNode } from "react"
import Markdown from "react-native-markdown-display"
import { ViewStyle, Alert } from "react-native"
import * as FileSystem from "expo-file-system"
import * as MediaLibrary from "expo-media-library"

interface SaveableImageProps {
  src: string
  children: ReactNode
}

const SaveableImage = memo(function SaveableImage({ src, children }: SaveableImageProps) {
  const handleSaveImage = async (imageUrl: string) => {
    try {
      const { status } = await MediaLibrary.requestPermissionsAsync()
      if (status !== "granted") {
        Alert.alert("Permission needed", "Please grant permission to save images")
        return
      }

      const fileUri = FileSystem.documentDirectory + "temp_image.jpg"
      await FileSystem.downloadAsync(imageUrl, fileUri)
      const asset = await MediaLibrary.createAssetAsync(fileUri)
      await MediaLibrary.createAlbumAsync("Letta", asset, false)
      await FileSystem.deleteAsync(fileUri)
      Alert.alert("Success", "Image saved to gallery")
    } catch {
      Alert.alert("Error", "Failed to save image")
    }
  }

  return (
    <SimpleContextMenu
      actions={[
        {
          key: "save",
          title: "Save Image",
          iosIconName: { name: "square.and.arrow.down", weight: "bold" },
          androidIconName: "ic_menu_save",
          onPress: () => handleSaveImage(src),
        },
      ]}
    >
      {children}
    </SimpleContextMenu>
  )
})

interface AssistantMessageProps extends CardProps {}

function AssistantMessageInner({ content, style, ...props }: AssistantMessageProps) {
  const { theme } = useAppTheme()

  return (
    <Card
      style={[$assistantMessage, style]}
      disabled={true}
      ContentComponent={
        <Markdown
          rules={{
            image: (node) => {
              const { src, alt } = node.attributes
              return (
                <SaveableImage key={node.key} src={src}>
                  <AutoImage
                    source={{ uri: src }}
                    style={$markdownImage}
                    accessibilityLabel={alt}
                    resizeMode="contain"
                  />
                </SaveableImage>
              )
            },
          }}
          style={{
            blockquote: {
              borderLeftColor: theme.colors.text,
              backgroundColor: theme.colors.background,
              marginVertical: spacing.sm,
            },
            code_block: {
              backgroundColor: theme.colors.background,
              color: theme.colors.text,
              fontFamily: "monospace",
            },
            fence: {
              backgroundColor: theme.colors.background,
              color: theme.colors.text,
              fontFamily: "monospace",
              fontSize: 12,
            },
            code_inline: {
              backgroundColor: theme.colors.text,
              color: theme.colors.background,
            },
            body: { color: theme.colors.text },
          }}
        >
          {content || ""}
        </Markdown>
      }
      {...props}
    />
  )
}

export const AssistantMessage = memo(AssistantMessageInner)

const $assistantMessage: ViewStyle = {
  flexWrap: "wrap",
  flexDirection: "row",
  maxWidth: "100%",
  paddingHorizontal: spacing.md,
  paddingVertical: spacing.sm,
  alignItems: "flex-start",
  minHeight: 0,
}

const $markdownImage = {
  width: 250,
  height: 250,
  marginVertical: 5,
  marginHorizontal: "auto" as any,
}
