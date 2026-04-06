import { Platform, Dimensions } from "react-native"
import * as Application from "expo-application"

export const getDeviceInfo = async () => {
  const { width, height } = Dimensions.get("window")

  return JSON.stringify(
    {
      os: Platform.OS,
      osVersion: Platform.Version,
      // model: typeof Platform.OS === 'ios' ? 'iPad' : 'Mobile', // Basic heuristic
      model: Platform.select({
        ios: "iOS",
        macos: "macOS",
        android: "Android",
        default: "Unknown",
        web: "Web",
      }),
      screen: {
        width,
        height,
        scale: Dimensions.get("screen").scale,
      },
      app: {
        name: Application.applicationName,
        version: Application.nativeApplicationVersion,
        build: Application.nativeBuildVersion,
        id: Application.applicationId,
      },
    },
    null,
    2,
  )
}

export const getDeviceInfoDefinition = {
  name: "get_device_info",
  sourceCode: `def get_device_info():
    """
    Get information about the device running the client application.
    Returns JSON string with OS, Model, etc.
    """
    raise Exception("This tool executes client-side only")`,
}
