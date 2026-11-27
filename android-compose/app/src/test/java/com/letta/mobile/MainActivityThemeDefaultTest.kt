package com.letta.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Out of the box the app starts on the shared Baken palette — the exact scheme the desktop and the web
 * draw. The frame before the settings store answers must not be the wallpaper's colours either: the
 * dynamic-colour flow starts on `false`, so the first frame is the shared palette and Material You loads
 * only when the person opted into it in Settings (the stored default is pinned by SettingsRepositoryTest).
 */
class MainActivityThemeDefaultTest {
    @Test
    fun theFirstFrameIsNotTheWallpapersColours() {
        val source = File(MAIN_ACTIVITY).readText()
        val line = source.lineSequence().single { "getDynamicColor()" in it }
        assertTrue(
            "the dynamic-colour flow must start on the shared palette, not the SDK level: $line",
            line.contains("initialValue = false"),
        )
    }

    private companion object {
        /** Gradle runs the module's unit tests from the module directory. */
        const val MAIN_ACTIVITY = "src/main/java/com/letta/mobile/MainActivity.kt"
    }
}
