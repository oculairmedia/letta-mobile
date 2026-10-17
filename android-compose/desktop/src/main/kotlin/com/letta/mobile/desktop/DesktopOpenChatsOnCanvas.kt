package com.letta.mobile.desktop

import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.chat.display.OpenChatsOnCanvas
import com.letta.mobile.desktop.data.DesktopPreferences
import kotlinx.coroutines.Dispatchers

/** The process-wide "Open conversations on the canvas" preference; tests and previews provide their own. */
internal val LocalDesktopOpenChatsOnCanvas = staticCompositionLocalOf {
    OpenChatsOnCanvas(DesktopPreferences.store, Dispatchers.IO)
}
