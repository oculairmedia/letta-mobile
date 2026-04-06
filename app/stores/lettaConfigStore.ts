import { z } from "zod"
import { create } from "zustand"
import { createJSONStorage, persist } from "zustand/middleware"
import { zustandStorage } from "./store.storage"

type LettaMode = "cloud" | "selfhosted"

interface LettaConfig {
  id: string
  mode: LettaMode
  serverUrl: string
  accessToken: string
}

interface LettaConfigState {
  configs: LettaConfig[]
  activeConfigId: string | null
  setConfig: (
    config: Partial<LettaConfig> & { mode: LettaMode; accessToken: string },
  ) => LettaConfig
  setActiveConfig: (id: string | null) => void
  getActiveConfig: () => LettaConfig | null
  deleteConfig: (id: string) => void
}

const configSchema = z.object({
  mode: z.enum(["cloud", "selfhosted"]),
  serverUrl: z
    .string()
    .min(1)
    .transform((val) => val.trim())
    .optional(),
  accessToken: z.string().transform((val) => val.trim()),
})

export const isValidConfig = (mode: LettaMode, serverUrl: string, accessToken: string) => {
  const result = configSchema.safeParse({ mode, serverUrl, accessToken })
  if (!result.success) return false

  // For cloud mode, we only need access token
  if (mode === "cloud") return !!accessToken

  // For selfhosted mode, we need both server URL and access token
  return mode === "selfhosted" && !!serverUrl && !!accessToken
}

const generateId = () => {
  return `letta-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`
}

export const useLettaConfigStore = create<LettaConfigState>()(
  persist(
    (set, get) => ({
      configs: [],
      activeConfigId: null,

      setConfig: (config) => {
        const id = config.id || generateId()
        const newConfig: LettaConfig = {
          id,
          mode: config.mode,
          serverUrl: config.serverUrl || "",
          accessToken: config.accessToken,
        }

        set((state) => {
          const configs = state.configs.filter((c) => c.id !== id)
          return {
            configs: [...configs, newConfig],
            activeConfigId: id,
          }
        })

        return newConfig
      },

      setActiveConfig: (id) => {
        // Only set active config if it exists in configs
        const state = get()
        const configExists = state.configs.some((c) => c.id === id)
        if (configExists || id === null) {
          set({ activeConfigId: id })
        }
      },

      getActiveConfig: () => {
        const state = get()
        return state.configs.find((c) => c.id === state.activeConfigId) || null
      },

      deleteConfig: (id) => {
        set((state) => {
          const configs = state.configs.filter((c) => c.id !== id)
          // If we're deleting the active config, set activeConfigId to null
          const activeConfigId = state.activeConfigId === id ? null : state.activeConfigId
          return { configs, activeConfigId }
        })
        // if there is another config, set it as active
        const state = get()
        const configs = state.configs
        if (configs.length > 0) {
          set({ activeConfigId: configs[0]?.id })
        }
      },
    }),
    {
      name: "letta-config",
      storage: createJSONStorage(() => zustandStorage),
      partialize: (state) => ({
        configs: state.configs,
        activeConfigId: state.activeConfigId,
      }),
    },
  ),
)
