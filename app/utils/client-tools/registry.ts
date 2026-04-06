import { getDeviceInfo, getDeviceInfoDefinition } from "./definitions/device-info"

type ClientToolFunction = (args: string) => Promise<string>

export const clientTools: Record<string, ClientToolFunction> = {
  get_device_info: async (_args: string) => {
    return await getDeviceInfo()
  },
}

export const clientToolDefinitions = [getDeviceInfoDefinition]
