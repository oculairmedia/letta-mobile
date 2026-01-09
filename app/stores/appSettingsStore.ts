import { create } from "zustand"
import { createJSONStorage, persist } from "zustand/middleware"
import { zustandStorage } from "./store.storage"

type AppTheme = "light" | "dark"

interface AppSettingsState {
  appTheme: AppTheme
  setAppTheme: (theme: AppTheme) => void
}

export const useAppSettingsStore = create<AppSettingsState>()(
  persist(
    (set) => ({
      appTheme: "light",
      setAppTheme: (theme) => set({ appTheme: theme }),
    }),
    {
      name: "app-settings",
      storage: createJSONStorage(() => zustandStorage),
    },
  ),
)
