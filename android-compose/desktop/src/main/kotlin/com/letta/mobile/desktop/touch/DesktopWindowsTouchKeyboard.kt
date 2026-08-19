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
import java.awt.Toolkit
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Show/hide the platform touch keyboard. Swapped for a fake in tests. */
internal interface DesktopTouchKeyboardController {
    fun show()
    fun hide()
}

/**
 * Reflectively binds the JDK's own private touch-keyboard natives on
 * `sun.awt.windows.WToolkit`: `showTouchKeyboard(boolean)` and
 * `hideTouchKeyboard()`.
 *
 * Measured directly on Windows 11 touch hardware: calling these two natives —
 * reflectively, from the EDT, with a normal focused window — raises and
 * dismisses the real touch keyboard. This is what the JDK itself calls from
 * `WToolkit.showOrHideTouchKeyboard`, which never fires for Compose because
 * that method only triggers for a focused `java.awt.TextComponent` /
 * `javax.swing.text.JTextComponent`, and Compose paints its text fields into a
 * Skia canvas — the focused AWT component is always the Compose panel. Calling
 * the private natives directly sidesteps that focused-component gate entirely.
 *
 * Needs `--add-opens java.desktop/sun.awt.windows=ALL-UNNAMED` (a different
 * package from the `sun.awt` open `DesktopTouchEventAccessor` uses). Without
 * it, [bindOrNull] returns null and the caller falls back.
 */
internal object DesktopJdkTouchKeyboardAccessor {

    /**
     * Binds against [toolkit] — defaults to the live AWT toolkit
     * (`sun.awt.windows.WToolkit` on Windows) — or returns null when the
     * methods cannot be found (no `--add-opens`, non-Windows JDK build, a
     * future JDK that renames or drops them).
     *
     * [toolkit] is a parameter purely so a test can bind against a stand-in
     * object exposing matching method signatures and prove the invoke wrapper
     * never propagates a failure, without touching the real AWT toolkit or
     * popping a real keyboard on a CI box.
     */
    fun bindOrNull(toolkit: Any = Toolkit.getDefaultToolkit()): DesktopTouchKeyboardController? = runCatching {
        val showMethod = toolkit.javaClass
            .getDeclaredMethod("showTouchKeyboard", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val hideMethod = toolkit.javaClass
            .getDeclaredMethod("hideTouchKeyboard")
            .apply { isAccessible = true }

        object : DesktopTouchKeyboardController {
            override fun show() {
                // A per-call throw must never escape into AWT's dispatch loop.
                runCatching { showMethod.invoke(toolkit, true) }
            }

            override fun hide() {
                runCatching { hideMethod.invoke(toolkit) }
            }
        }
    }.getOrNull()
}

/**
 * Raises and dismisses the Windows touch keyboard for Compose text fields.
 *
 * The primary mechanism is [DesktopJdkTouchKeyboardAccessor]: the JDK's own
 * private `WToolkit.showTouchKeyboard`/`hideTouchKeyboard` natives, called
 * reflectively. Verified visually on real Windows 11 touch hardware.
 *
 * ### Why not COM `ITipInvocation` (the previous approach)
 *
 * The original implementation asked TabTip directly via the undocumented
 * `ITipInvocation` COM interface, with a `TabTip.exe` launch as fallback. Both
 * are dead on measured Windows 11 hardware:
 *
 * - `CoCreateInstance(CLSID_UIHostNoLaunch, ..., IID_ITipInvocation, ...)`
 *   returns `hr=0x80040154` (`REGDB_E_CLASSNOTREG`). `UIHostNoLaunch` is not a
 *   registered COM class on this Windows 11 build, so this path can never
 *   succeed here — it is not a transient failure.
 * - The `TabTip.exe`-relaunch fallback is a silent no-op whenever TabTip is
 *   already running (its window already exists), which is the common case —
 *   so what the user actually experienced was total silence.
 * - Windows 11 hosts the touch keyboard in `TextInputHost.exe`, not the
 *   legacy TabTip window, so `FindWindow("IPTip_Main_Window")` plus
 *   `IsWindowVisible`/`WS_DISABLED` is a **proven false negative**: while the
 *   keyboard was genuinely on screen, that check still reported
 *   `visible=false disabled=true`. Any visibility gate built on it is
 *   actively wrong on this OS, not merely unreliable — do not resurrect it.
 *
 * The COM/TabTip path is kept only as a secondary fallback for pre-Win11
 * hosts where `UIHostNoLaunch` might still resolve; the JDK route above is
 * always tried first and is expected to be the only path that ever fires on
 * current hardware.
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

    private const val SC_CLOSE = 0xF060

    /** Used only by the legacy COM/TabTip fallback below. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "letta-touch-keyboard").apply { isDaemon = true }
    }

    private val comInitialized = AtomicBoolean(false)
    private val jdkBindFailureLogged = AtomicBoolean(false)
    private val fallbackFailureLogged = AtomicBoolean(false)

    /**
     * Bound once, up front, the same fail-safe way
     * [DesktopTouchEventAccessor.bindOrNull] binds: null means "unavailable",
     * logged once, and every call becomes the COM/TabTip fallback.
     */
    private val jdkController: DesktopTouchKeyboardController? by lazy {
        DesktopJdkTouchKeyboardAccessor.bindOrNull().also { bound ->
            if (bound == null && jdkBindFailureLogged.compareAndSet(false, true)) {
                DesktopCrashReporter.logCrash(
                    IllegalStateException(
                        "WToolkit.showTouchKeyboard/hideTouchKeyboard are unavailable; expected " +
                            "--add-opens java.desktop/sun.awt.windows=ALL-UNNAMED. Falling back to " +
                            "the legacy COM/TabTip path, which is known dead on Windows 11.",
                    ),
                    context = "windows touch keyboard bind",
                )
            }
        }
    }

    override fun show() {
        if (Platform.Current != Platform.Windows) return
        // Matches the JDK's own usage: WToolkit calls this synchronously from
        // the EDT, and the native call itself is a fast, non-blocking IPC
        // nudge to TextInputHost — no dedicated thread needed for this path.
        val jdk = jdkController
        if (jdk != null) {
            jdk.show()
            return
        }
        submitFallback("show touch keyboard") {
            // Toggle really does toggle, so only invoke it when the fallback
            // is actually reachable and TabTip isn't already visible; there is
            // no reliable visibility check on Windows 11 (see class doc), but
            // this fallback is only exercised pre-Win11 where the legacy
            // window state check is trustworthy.
            if (!isLegacyTabTipVisible()) {
                if (!toggleViaComHost()) launchTabTip()
            }
        }
    }

    override fun hide() {
        if (Platform.Current != Platform.Windows) return
        val jdk = jdkController
        if (jdk != null) {
            jdk.hide()
            return
        }
        submitFallback("hide touch keyboard") {
            val hwnd = User32.INSTANCE.FindWindow(TABTIP_WINDOW_CLASS, null) ?: return@submitFallback
            User32.INSTANCE.PostMessage(
                hwnd,
                WinUser.WM_SYSCOMMAND,
                WinDef.WPARAM(SC_CLOSE.toLong()),
                WinDef.LPARAM(0),
            )
        }
    }

    private fun submitFallback(context: String, action: () -> Unit) {
        executor.execute {
            runCatching {
                initializeCom()
                action()
            }.onFailure { error ->
                // Never fatal: the app is fully usable with a hardware keyboard.
                if (fallbackFailureLogged.compareAndSet(false, true)) {
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

    /**
     * Legacy Windows 8/10 visibility check. Measured false-negative on
     * Windows 11 (see class doc) — never call this to gate the JDK path
     * above; it exists only for the pre-Win11 COM fallback.
     */
    private fun isLegacyTabTipVisible(): Boolean {
        val hwnd = User32.INSTANCE.FindWindow(TABTIP_WINDOW_CLASS, null) ?: return false
        val style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        return User32.INSTANCE.IsWindowVisible(hwnd) && (style and WinUser.WS_DISABLED) == 0
    }

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
