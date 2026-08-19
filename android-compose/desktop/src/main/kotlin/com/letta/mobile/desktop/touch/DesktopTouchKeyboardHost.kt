package com.letta.mobile.desktop.touch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor

/**
 * Wraps the desktop content so every Compose text-input session can raise the
 * Windows touch keyboard when — and only when — the session was started by a
 * finger.
 *
 * [InterceptPlatformTextInput] is the supported hook for this: Compose calls
 * the interceptor when a text field starts an input method and suspends inside
 * it for the lifetime of the session, so the `finally` block is the exact
 * moment the field loses focus. That is a much steadier signal than watching
 * AWT focus, which never moves off the Compose panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopTouchKeyboardHost(
    controller: DesktopTouchKeyboardController = DesktopWindowsTouchKeyboard,
    origin: DesktopTouchOriginTracker = DesktopTouchOrigin,
    content: @Composable () -> Unit,
) {
    val gate = remember(controller, origin) { DesktopTouchKeyboardSessionGate(controller, origin) }
    val interceptor = remember(gate) {
        PlatformTextInputInterceptor { request, nextHandler ->
            val raised = gate.begin()
            try {
                nextHandler.startInputMethod(request)
            } finally {
                gate.end(raised)
            }
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
