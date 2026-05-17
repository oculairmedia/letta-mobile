package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
@OptIn(ExperimentalRoborazziApi::class)
class A2uiRendererScreenshotTest {
    @Test
    fun coreWidgetsLightTheme() {
        captureA2uiSurface(
            name = "a2ui_core_widgets_light",
            appTheme = AppTheme.LIGHT,
        )
    }

    @Test
    fun coreWidgetsDarkTheme() {
        captureA2uiSurface(
            name = "a2ui_core_widgets_dark",
            appTheme = AppTheme.DARK,
        )
    }

    private fun captureA2uiSurface(
        name: String,
        appTheme: AppTheme,
    ) {
        val manager = confirmationSurfaceManager()
        val surface = manager.surface(SurfaceId)

        captureRoboImage("src/test/snapshots/images/$name.png", ScreenshotOptions) {
            A2uiSnapshotTheme(appTheme = appTheme) {
                A2uiSurfaceRenderer(surface = surface)
            }
        }
    }

    @Composable
    private fun A2uiSnapshotTheme(
        appTheme: AppTheme,
        content: @Composable () -> Unit,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            LettaTheme(
                appTheme = appTheme,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                Box(
                    modifier = Modifier
                        .width(SnapshotWidth)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                ) {
                    content()
                }
            }
        }
    }

    private companion object {
        val SnapshotWidth = 360.dp
        val ScreenshotOptions = RoborazziOptions(
            captureType = RoborazziOptions.CaptureType.Screenshot(),
        )
    }
}
