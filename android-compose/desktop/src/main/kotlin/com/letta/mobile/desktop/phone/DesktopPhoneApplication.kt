package com.letta.mobile.desktop.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import com.letta.mobile.desktop.CrashReportingExceptionHandlerFactory
import com.letta.mobile.desktop.DesktopAppShellBindings
import com.letta.mobile.desktop.DesktopDeepLinkRequest
import com.letta.mobile.desktop.DesktopJewelTheme
import com.letta.mobile.desktop.DesktopQuickQueryCoordinator
import com.letta.mobile.desktop.DesktopThemeOverride
import com.letta.mobile.desktop.LettaDesktopApp
import com.letta.mobile.desktop.LocalDesktopThemeOverride
import com.letta.mobile.desktop.MascotCursorCapture
import com.letta.mobile.desktop.avatar.rive.DesktopMascotHost
import com.letta.mobile.desktop.markdown.DesktopMermaidDiagramRenderer
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotIdentityRegistry
import com.letta.mobile.ui.mascot.MascotTransport
import com.letta.mobile.ui.markdown.LocalMermaidDiagramRenderer
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.darkmodedetector.isSystemInDarkMode
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `:desktop:runPhone` (LETTA_DESKTOP_PHONE=1): the real desktop app - its agents, conversations and
 * boards over the configured transport - in a phone-sized window, with the shared chat page in the
 * Touch idiom and the desktop shell folded into a phone's (see LettaDesktopApp's [LocalDesktopPhone]
 * branches). No tray, quick-query window, jump list or single-instance lock: it is a preview, and it
 * runs beside a normal desktop instance.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun runDesktopPhoneApplication(args: Array<String>) {
    println("PHONE: launching Letta Desktop as a phone ($PHONE_ENV_VARIABLE). Press F1 in the window for the shortcuts.")
    val deepLinks = MutableStateFlow<DesktopDeepLinkRequest?>(null)
    val quickQuery = DesktopQuickQueryCoordinator()
    nucleusApplication(args = args, backend = NucleusBackend.Awt, enableSingleInstance = false) {
        val nucleusScope = this
        val systemDark = isSystemInDarkMode()
        val state = remember { DesktopPhoneState(initialDark = systemDark, initialZoom = phoneZoomFor(PhoneDevice.Pixel9Pro)) }
        val chrome = remember(state) { DesktopPhoneChrome(state) }
        val mascots = remember { MascotIdentityRegistry() }
        val mascotTransport = remember { MascotTransport() }
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides CrashReportingExceptionHandlerFactory) {
            DesktopPhoneWindow(state, titlePrefix = "Letta Phone", onCloseRequest = nucleusScope::exitApplication) { window ->
                CompositionLocalProvider(
                    LocalMermaidDiagramRenderer provides DesktopMermaidDiagramRenderer,
                    LocalMascotHost provides DesktopMascotHost,
                    LocalMascotRegistry provides mascots,
                    LocalMascotTransport provides mascotTransport,
                    LocalDesktopPhone provides chrome,
                    LocalDesktopThemeOverride provides DesktopThemeOverride(dark = state.dark, phoneMetrics = true),
                ) {
                    // Jewel styles the desktop panes the phone's drawer reuses (rail, sidebar).
                    DesktopJewelTheme {
                        MascotCursorCapture(mascots) {
                            LettaDesktopApp(
                                shell = DesktopAppShellBindings(
                                    nucleusApplicationScope = nucleusScope,
                                    window = window,
                                    deepLinks = deepLinks,
                                    quickQuery = quickQuery,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
