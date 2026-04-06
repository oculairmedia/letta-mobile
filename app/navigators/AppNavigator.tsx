/**
 * The app navigator (formerly "AppNavigator" and "MainNavigator") is used for the primary
 * navigation flows of your app.
 * Generally speaking, it will contain an auth flow (registration, login, forgot password)
 * and a "main" flow which the user will use once logged in.
 */

import * as Screens from "@/screens"
import { ThemeContexts } from "@/theme"
import { useAppTheme, useThemeProvider } from "@/utils/useAppTheme"
import { NavigationContainer, NavigatorScreenParams } from "@react-navigation/native"
import { createNativeStackNavigator, NativeStackScreenProps } from "@react-navigation/native-stack"

import { useAppSettingsStore } from "@/stores/appSettingsStore"
import { useLettaConfigStore } from "@/stores/lettaConfigStore"
import { ComponentProps, useMemo } from "react"
import Config from "../config"
import { AgentDrawerNavigator, AgentDrawerParamList } from "./AgentDrawerNavigator"
import { navigationRef, useBackButtonHandler } from "./navigationUtilities"
/**
 * This type allows TypeScript to know what routes are defined in this navigator
 * as well as what properties (if any) they might take when navigating to them.
 *
 * If no params are allowed, pass through `undefined`. Generally speaking, we
 * recommend using your Zustand store(s) to keep application state
 * rather than passing state through navigation params.
 *
 * For more information, see this documentation:
 *   https://reactnavigation.org/docs/params/
 *   https://reactnavigation.org/docs/typescript#type-checking-the-navigator
 *   https://reactnavigation.org/docs/typescript/#organizing-types
 */
export type AppStackParamList = {
  Welcome: undefined
  Developer: undefined

  Login: undefined
  LettaConfig: undefined
  AgentList: undefined
  AgentDrawer: NavigatorScreenParams<AgentDrawerParamList>
  Studio: undefined
  Templates: undefined
  MCP: undefined
  EditAgent: { agentId: string }
  ConfigList: undefined
  // IGNITE_GENERATOR_ANCHOR_APP_STACK_PARAM_LIST
}

/**
 * This is a list of all the route names that will exit the app if the back button
 * is pressed while in that screen. Only affects Android.
 */
const exitRoutes = Config.exitRoutes

export type AppStackScreenProps<T extends keyof AppStackParamList> = NativeStackScreenProps<
  AppStackParamList,
  T
>

// Documentation: https://reactnavigation.org/docs/stack-navigator/
const Stack = createNativeStackNavigator<AppStackParamList>()

const AppStack = () => {
  const { configs } = useLettaConfigStore()

  const {
    theme: { colors },
  } = useAppTheme()

  const canAccess = useMemo(() => configs.length > 0, [configs])

  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
        navigationBarColor: colors.background,
        contentStyle: { backgroundColor: colors.background },
      }}
      initialRouteName="AgentList"
    >
      {canAccess ? (
        <Stack.Group>
          <Stack.Screen name="AgentList" component={Screens.AgentListScreen} />
          <Stack.Screen name="Developer" component={Screens.DeveloperScreen} />
          <Stack.Screen name="AgentDrawer" component={AgentDrawerNavigator} />
          <Stack.Screen name="Studio" component={Screens.StudioScreen} />
          <Stack.Screen name="Templates" component={Screens.TemplatesScreen} />
          <Stack.Screen name="MCP" component={Screens.MCPScreen} />
          <Stack.Screen name="EditAgent" component={Screens.EditAgentScreen} />
          <Stack.Screen name="ConfigList" component={Screens.ConfigListScreen} />
          {/* IGNITE_GENERATOR_ANCHOR_APP_STACK_SCREENS */}
          {/** 🔥 Your screens go here */}
        </Stack.Group>
      ) : (
        <Stack.Group>
          <Stack.Screen name="Welcome" component={Screens.DeveloperScreen} />
        </Stack.Group>
      )}
    </Stack.Navigator>
  )
}

export interface NavigationProps extends Partial<ComponentProps<typeof NavigationContainer>> {}

export const AppNavigator = (props: NavigationProps) => {
  const { appTheme, setAppTheme } = useAppSettingsStore()
  const { themeScheme, navigationTheme, setThemeContextOverride, ThemeProvider } = useThemeProvider(
    appTheme as ThemeContexts,
  )

  useBackButtonHandler((routeName) => exitRoutes.includes(routeName))

  return (
    <ThemeProvider
      value={{
        themeScheme,
        setThemeContextOverride: (newTheme) => {
          setThemeContextOverride(newTheme as NonNullable<ThemeContexts>)
          setAppTheme(newTheme as NonNullable<ThemeContexts>)
        },
      }}
    >
      <NavigationContainer ref={navigationRef} theme={navigationTheme} {...props}>
        <AppStack />
      </NavigationContainer>
    </ThemeProvider>
  )
}
