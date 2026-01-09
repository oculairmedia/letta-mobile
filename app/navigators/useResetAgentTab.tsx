import { useEffect } from "react"
import { useNavigation } from "@react-navigation/native"
import { useAgentStore } from "@/providers/AgentProvider"
import { AppStackScreenProps } from "./AppNavigator"

export const useResetAgentTab = () => {
  const navigation = useNavigation<AppStackScreenProps<"AgentList">["navigation"]>()
  const agentId = useAgentStore((state) => state.agentId)

  useEffect(() => {
    if (!agentId) {
      console.log("NO AGENT ID: ", agentId)
      navigation.reset({
        index: 0,
        routes: [{ name: "AgentList" }],
      })
    }
  }, [agentId, navigation])
}
