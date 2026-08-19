package com.letta.mobile.desktop.touch

import com.letta.mobile.desktop.DesktopCrashReporter
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import dev.nucleusframework.core.runtime.Platform
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Show/hide the platform touch keyboard. Swapped for a fake in tests. */
internal interface DesktopTouchKeyboardController {
    fun show()
    fun hide()
}

/**
 * Raises and dismisses the Windows touch keyboard (TabTip) for Compose text
 * fields.
 *
 * Windows will not do this by itself. `WToolkit.showOrHideTouchKeyboard` bails
 * out immediately unless the focused AWT component is a `TextComponent` or a
 * `JTextComponent`; Compose paints its text fields into a Skia canvas, so the
 * focused component is always the Compose panel and the private native
 * `showTouchKeyboard` is never reached. Compose's own text path drives AWT
 * `InputMethodRequests` (IME), which is unrelated to TabTip, and Compose
 * accessibility is Java Access Bridge rather than UI Automation, so Windows'
 * UIA-based auto-invoke has nothing to latch onto either.
 *
 * So we ask TabTip directly. Showing goes through the undocumented but stable
 * `ITipInvocation` COM interface (the same one the shell uses); dismissal is a
 * `WM_SYSCOMMAND`/`SC_CLOSE` to TabTip's own window, because the public
 * `IFrameworkInputPane` surface exposes no `Hide`. If COM is unavailable —
 * TabTip not running, service disabled — showing falls back to launching
 * `TabTip.exe`.
 *
 * All work runs on a dedicated single-thread executor: it keeps COM
 * initialization off the AWT event dispatch thread and keeps a slow
 * `TabTip.exe` launch from stalling a frame.
 */
internal object DesktopWindowsTouchKeyboard : DesktopTouchKeyboardController {

    /** CLSID_UIHostNoLaunch — resolves only while TabTip is already running. */
    private val CLSID_UI_HOST_NO_LAUNCH = Guid.CLSID("4CE576FA-83DC-4F88-951C-9D0782B4E376")

    /** IID_ITipInvocation. */
    private val IID_TIP_INVOCATION = Guid.IID("37c994e7-432b-4834-a2f7-dce1f13b834b")

    /** `ITipInvocation::Toggle` sits directly after IUnknown's three slots. */
    private const val VTABLE_TOGGLE = 3

    private const val CLSCTX_INPROC_HANDLER = 0x10

    /** TabTip's top-level window class, unchanged since Windows 8. */
    private const val TABTIP_WINDOW_CLASS = "IPTip_Main_Window"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "letta-touch-keyboard").apply { isDaemon = true }
    }

    private val comInitialized = AtomicBoolean(false)
    private val failureLogged = AtomicBoolean(false)

    override fun show() {
        if (Platform.Current != Platform.Windows) return
        submit("show touch keyboard") {
            // Toggle really does toggle, so a second show while the pane is up
            // would dismiss it — check first.
            if (!isTouchKeyboardVisible()) {
                if (!toggleViaComHost()) launchTabTip()
            }
        }
    }

    override fun hide() {
        if (Platform.Current != Platform.Windows) return
        submit("hide touch keyboard") {
            val hwnd = User32.INSTANCE.FindWindow(TABTIP_WINDOW_CLASS, null) ?: return@submit
            User32.INSTANCE.PostMessage(
                hwnd,
                WinUser.WM_SYSCOMMAND,
                WinDef.WPARAM(SC_CLOSE.toLong()),
                WinDef.LPARAM(0),
            )
        }
    }

    private fun submit(context: String, action: () -> Unit) {
        executor.execute {
            runCatching {
                initializeCom()
                action()
            }.onFailure { error ->
                // Never fatal: the app is fully usable with a hardware keyboard.
                if (failureLogged.compareAndSet(false, true)) {
                    DesktopCrashReporter.logCrash(error, context = context)
                }
            }
        }
    }

    private fun initializeCom() {
        if (!comInitialized.compareAndSet(false, true)) return
        // S_FALSE / RPC_E_CHANGED_MODE both mean "already initialized", which is
        // fine — we only need an apartment, not ownership of it.
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
    }

    private fun toggleViaComHost(): Boolean {
        val result = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            CLSID_UI_HOST_NO_LAUNCH,
            null,
            CLSCTX_INPROC_HANDLER,
            IID_TIP_INVOCATION,
            result,
        )
        if (!COMUtils.SUCCEEDED(hr) || result.value == null) return false
        val tip = TipInvocation(result.value)
        return try {
            tip.toggle(User32.INSTANCE.GetDesktopWindow())
        } finally {
            tip.Release()
        }
    }

    private fun launchTabTip(): Boolean {
        val commonFiles = System.getenv("CommonProgramFiles") ?: return false
        val tabTip = File(commonFiles, "microsoft shared\\ink\\TabTip.exe")
        if (!tabTip.isFile) return false
        ProcessBuilder(tabTip.absolutePath).start()
        return true
    }

    private fun isTouchKeyboardVisible(): Boolean {
        val hwnd = User32.INSTANCE.FindWindow(TABTIP_WINDOW_CLASS, null) ?: return false
        // TabTip keeps its window alive and merely disables it while dismissed,
        // so IsWindowVisible alone reports a false positive.
        val style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        return User32.INSTANCE.IsWindowVisible(hwnd) && (style and WinUser.WS_DISABLED) == 0
    }

    private const val SC_CLOSE = 0xF060

    /**
     * Minimal `ITipInvocation` binding. JNA has no type library support, so the
     * single method is called by vtable slot.
     */
    private class TipInvocation(pointer: Pointer) : Unknown(pointer) {
        fun toggle(hwnd: WinDef.HWND): Boolean =
            COMUtils.SUCCEEDED(_invokeNativeInt(VTABLE_TOGGLE, arrayOf(this.pointer, hwnd)))
    }
}

/**
 * Decides whether a Compose text-input session should raise the touch keyboard,
 * and pairs each show with exactly one hide.
 *
 * Split out from the Compose interceptor so the "only for touch" rule is unit
 * testable: mouse and precision-touchpad users must never see TabTip, and text
 * fields are also focused programmatically (the chat composer autofocuses) and
 * by Tab, neither of which should summon a keyboard.
 */
internal class DesktopTouchKeyboardSessionGate(
    private val controller: DesktopTouchKeyboardController,
    private val origin: DesktopTouchOriginTracker,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Returns true when the keyboard was raised; pass that back to [end]. */
    fun begin(): Boolean {
        val raise = origin.wasTouch(nowMillis())
        if (raise) controller.show()
        return raise
    }

    fun end(raised: Boolean) {
        if (raised) controller.hide()
    }
}
