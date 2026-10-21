package com.letta.mobile.desktop

import com.letta.mobile.desktop.canvas.DesktopNotebookCanvasStore
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.DesktopLettaConfigStore
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import com.letta.mobile.desktop.input.TabletBridge
import com.letta.mobile.desktop.runtime.DesktopLocalBackendDirectorySettings
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopRuntimeLaunchSettings
import com.letta.mobile.desktop.security.DesktopSecretVaults
import com.letta.mobile.desktop.security.EncryptingSecureSettingsStore
import com.letta.mobile.desktop.touch.DesktopWindowsTouchKeyboard
import dev.nucleusframework.core.runtime.Platform
import kotlin.concurrent.thread

/**
 * The desktop's settings, read once per process: the vault-encrypted settings file (DPAPI on
 * Windows) and the active backend config. Lazy, so whichever thread asks first does the reading
 * and the other waits for that one result instead of reading again.
 */
internal object DesktopStartupSettings {
    val secureSettingsStore: EncryptingSecureSettingsStore by lazy {
        EncryptingSecureSettingsStore(
            delegate = DesktopFileSecureSettingsStore(),
            vault = DesktopSecretVaults.forCurrentOs(defaultDesktopStateDirectory()),
        ).also { store ->
            // Must run before the first local-runtime spawn so a saved directory override is
            // honored from the app's very first connection, not just after a later Settings visit.
            DesktopLocalBackendDirectorySettings.applyStoredOverride(store)
            // F05: stable instance id and the default working directory for the child.
            DesktopRuntimeLaunchSettings.applyStored(store)
        }
    }

    val configStore: DesktopLettaConfigStore by lazy { DesktopLettaConfigStore(secureSettingsStore) }
}

/**
 * Startup work that needs no UI thread, begun from main() so it overlaps creating the window and
 * its graphics device instead of running inside the first composition on the Swing EDT. Each step
 * is a lazy value or an object initialiser the first composition would otherwise run itself; done
 * here first, the EDT finds it ready, or waits only for what is left of it. Nothing about what is
 * built changes, only the thread and the moment.
 */
internal object DesktopStartupPrewarm {
    private val steps: List<() -> Unit> = buildList {
        add { DesktopStartupSettings.configStore }
        add { DesktopLocalRuntimeHost.appServer }
        add { DesktopNotebookCanvasStore.documents }
        add { TabletBridge.available }
        if (Platform.Current == Platform.Windows) add { DesktopWindowsTouchKeyboard }
    }

    fun start() {
        thread(name = "startup-prewarm", isDaemon = true) {
            // A failure here is not reported here: the app meets it where it first uses that piece.
            steps.forEach { step -> runCatching(step) }
        }
    }
}
