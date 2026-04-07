import { Alert } from "react-native"
import { Button } from "@/components/Button"
import { useResetChatMessages } from "@/hooks/use-agent-messages"

interface ResetChatProps {
  agentId: string
}

export function ResetChat({ agentId }: ResetChatProps) {
  const { mutate: resetChatMessages, isPending } = useResetChatMessages()

  const handleResetChat = () => {
    Alert.alert("Would you like initial messages?", "", [
      {
        text: "No",
        style: "cancel",
        onPress: () => resetChatMessages({ agentId, add_default_initial_messages: false }),
      },
      {
        text: "Reset and Add Default Messages",
        style: "destructive",
        onPress: () => resetChatMessages({ agentId, add_default_initial_messages: true }),
      },
    ])
  }

  return (
    <Button
      preset="destructive"
      onPress={() =>
        Alert.alert("Are you sure you want to clear the chat?", "This action cannot be undone.", [
          { text: "Cancel", style: "cancel" },
          { text: "Clear Chat", onPress: () => handleResetChat() },
        ])
      }
      text="Clear Chat"
      loading={isPending}
    />
  )
}
