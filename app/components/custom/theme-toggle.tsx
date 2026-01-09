import { Button, Icon, Switch } from "@/components"
import { useAppTheme } from "@/utils/useAppTheme"
import { FC, Fragment } from "react"
import { useAppSettingsStore } from "@/stores/appSettingsStore"

interface ThemeToggleProps {
  mode?: "switch" | "icon"
}

export const ThemeToggle: FC<ThemeToggleProps> = ({ mode = "switch" }) => {
  const { theme, setThemeContextOverride } = useAppTheme()
  const setAppTheme = useAppSettingsStore((state) => state.setAppTheme)

  const handleThemeToggle = () => {
    const newTheme = theme.isDark ? "light" : "dark"
    setThemeContextOverride(newTheme)
    setAppTheme(newTheme)
  }

  return (
    <Fragment>
      {mode === "switch" && (
        <Switch
          accessibilityMode="icon"
          accessibilityOnIcon="Sun"
          accessibilityOffIcon="Moon"
          value={theme.isDark}
          onValueChange={handleThemeToggle}
        />
      )}

      {mode === "icon" && (
        <Button preset="icon" onPress={handleThemeToggle}>
          <Icon icon="Sun" size={24} color={theme.colors.text} />
        </Button>
      )}
    </Fragment>
  )
}
