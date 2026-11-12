package com.letta.mobile.ui.secrets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.window.DialogWindowProvider
import com.letta.mobile.data.secrets.client.SecureProtection
import com.letta.mobile.data.secrets.client.SecureSurfaceCapabilities

/**
 * Android: while the secure form is composed, the window that hosts it (a dialog's or sheet's own
 * window when the form is in one, the activity's otherwise) carries `FLAG_SECURE`, so screenshots,
 * screen recordings, casting to an insecure display and the recents thumbnail show it blank; the
 * hosting Compose view is excluded from autofill (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`)
 * and, on Android 15 and later, marked `CONTENT_SENSITIVITY_SENSITIVE` so screen sharing hides it.
 * Each setting is reference-counted per window or view and put back to what it was before the first
 * secure surface once the last one leaves, so two secure surfaces never clear each other.
 *
 * Not guaranteed: a rooted device, an accessibility service with screen access, or a keyboard that
 * ignores the password input type and the no-learning flag ([SecureTextInput]) can still see input;
 * `FLAG_SECURE` must be confirmed on a device (a blank `screencap`), which unit tests cannot do.
 */
@Composable
actual fun SecureSurfaceEffect(): SecureSurfaceCapabilities {
    val view = LocalView.current
    val surface = remember(view) { AndroidSecureSurface(view) }
    var capabilities by remember { mutableStateOf(SecureSurfaceCapabilities.SharedOnly) }
    DisposableEffect(surface) {
        capabilities = surface.enter()
        onDispose { surface.exit() }
    }
    return capabilities
}

/**
 * Android: the keyboard opened for a field inside [content] gets `IME_FLAG_NO_PERSONALIZED_LEARNING`
 * on top of the password input type the form asks for, so a conforming keyboard neither learns
 * from nor suggests what is typed. Keyboards are free to ignore the flag.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun SecureTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = NoLearningInterceptor, content = content)
}

@OptIn(ExperimentalComposeUiApi::class)
private val NoLearningInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(NoLearningRequest(request))
}

/** [delegate]'s input connection, with the keyboard asked not to learn. */
private class NoLearningRequest(private val delegate: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = delegate.createInputConnection(outAttributes)
        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return connection
    }
}

/** The per-window and per-view counts every secure surface in the process shares. */
private object SecureLedgers {
    val secureFlag = RestoreLedger<Window, Boolean>()
    val autofill = RestoreLedger<View, Int>()
    val sensitivity = RestoreLedger<View, Int>()
}

/** The secure surface of the window and Compose view hosting [view]. */
internal class AndroidSecureSurface(private val view: View) : SecureSurface {
    private val window: Window? = view.hostingWindow()

    override fun enter(): SecureSurfaceCapabilities {
        val active = mutableSetOf(
            SecureProtection.MASKED_FIELD_STATE,
            SecureProtection.PASSWORD_KEYBOARD,
            SecureProtection.IME_LEARNING_DISABLED,
            SecureProtection.AUTOFILL_EXCLUDED,
        )
        if (window != null) {
            secureWindow(window)
            active += SecureProtection.SCREEN_CAPTURE_BLOCKED
        }
        if (SecureLedgers.autofill.acquire(view, view.importantForAutofill)) {
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            markSensitive()
            active += SecureProtection.SENSITIVE_CONTENT
        }
        return SecureSurfaceCapabilities(active)
    }

    override fun exit() {
        window?.let { hosting ->
            val release = SecureLedgers.secureFlag.release(hosting)
            if (release is LedgerRelease.Restore && !release.previous) hosting.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        (SecureLedgers.autofill.release(view) as? LedgerRelease.Restore)?.let { view.importantForAutofill = it.previous }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            (SecureLedgers.sensitivity.release(view) as? LedgerRelease.Restore)?.let { view.contentSensitivity = it.previous }
        }
    }

    private fun secureWindow(hosting: Window) {
        val alreadySecure = (hosting.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (SecureLedgers.secureFlag.acquire(hosting, alreadySecure) && !alreadySecure) {
            hosting.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun markSensitive() {
        if (SecureLedgers.sensitivity.acquire(view, view.contentSensitivity)) {
            view.contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
        }
    }
}

/** The window the view is drawn in: the nearest dialog or sheet window up the tree, else its activity's. */
private fun View.hostingWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return context.findActivity()?.window
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
