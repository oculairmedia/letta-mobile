import { AgentSettingsScreen, ChatScreen } from "@/screens"
import { SourceManagerScreen } from "@/screens/SourceManagerScreen"
import { ToolsScreen } from "@/screens/ToolsScreen"
import type { ThemedStyle } from "@/theme"
import { useAppTheme } from "@/utils/useAppTheme"
import { BottomTabScreenProps, createBottomTabNavigator } from "@react-navigation/bottom-tabs"
import { CompositeScreenProps } from "@react-navigation/native"
import { TextStyle, ViewStyle } from "react-native"
import { useSafeAreaInsets } from "react-native-safe-area-context"
import { Icon } from "../components"
import { AppStackParamList, AppStackScreenProps } from "./AppNavigator"
import { useResetAgentTab } from "./useResetAgentTab"
import { FC } from "react"

export type AgentTabParamList = {
  Chat: undefined
  Settings: undefined
  SourceManager: undefined
  Tools: undefined
}

/**
 * Helper for automatically generating navigation prop types for each route.
 *
 * More info: https://reactnavigation.org/docs/typescript/#organizing-types
 */
export type AgentTabScreenProps<T extends keyof AgentTabParamList> = CompositeScreenProps<
  BottomTabScreenProps<AgentTabParamList, T>,
  AppStackScreenProps<keyof AppStackParamList>
>

const Tab = createBottomTabNavigator<AgentTabParamList>()

type AgentTabNavigatorProps = {
  initialRouteName?: keyof AgentTabParamList
}

export const AgentTabNavigator: FC<AgentTabNavigatorProps> = ({ initialRouteName = "Chat" }) => {
  const { bottom } = useSafeAreaInsets()
  const {
    themed,
    theme: { colors },
  } = useAppTheme()
  useResetAgentTab()
  return (
    <Tab.Navigator
      initialRouteName={initialRouteName as keyof AgentTabParamList}
      screenOptions={{
        headerShown: false,
        tabBarShowLabel: false,
        tabBarHideOnKeyboard: true,
        tabBarStyle: themed([$tabBar, { height: bottom + 50 }]),
        tabBarActiveTintColor: colors.text,
        tabBarInactiveTintColor: colors.text,
        tabBarLabelStyle: themed($tabBarLabel),
        tabBarItemStyle: themed($tabBarItem),
      }}
    >
      <Tab.Screen
        name="Chat"
        component={ChatScreen}
        options={{
          tabBarLabel: "Chat",
          tabBarIcon: ({ focused }) => (
            <Icon
              icon="MessageCircleMore"
              color={focused ? colors.tint : colors.tintInactive}
              size={26}
            />
          ),
        }}
      />
      <Tab.Screen
        name="SourceManager"
        component={SourceManagerScreen}
        options={{
          tabBarLabel: "Source Manager",
          tabBarIcon: ({ focused }) => (
            <Icon icon="FileStack" color={focused ? colors.tint : colors.tintInactive} size={26} />
          ),
        }}
      />
      <Tab.Screen
        name="Tools"
        component={ToolsScreen}
        options={{
          tabBarLabel: "Tools",
          tabBarIcon: ({ focused }) => (
            <Icon icon="Wrench" color={focused ? colors.tint : colors.tintInactive} size={26} />
          ),
        }}
      />
      <Tab.Screen
        name="Settings"
        component={AgentSettingsScreen}
        options={{
          tabBarLabel: "Settings",
          tabBarIcon: ({ focused }) => (
            <Icon icon="settings" color={focused ? colors.tint : colors.tintInactive} size={26} />
          ),
        }}
      />
    </Tab.Navigator>
  )
}

const $tabBar: ThemedStyle<ViewStyle> = ({ colors }) => ({
  backgroundColor: colors.background,
  borderTopColor: colors.transparent,
})

const $tabBarItem: ThemedStyle<ViewStyle> = ({ spacing }) => ({
  paddingTop: spacing.xs,
})

const $tabBarLabel: ThemedStyle<TextStyle> = ({ colors }) => ({
  fontSize: 12,
  fontFamily: "Helvetica",
  color: colors.text,
})
