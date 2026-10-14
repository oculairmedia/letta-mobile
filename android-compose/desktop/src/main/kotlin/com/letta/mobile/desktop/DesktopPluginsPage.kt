package com.letta.mobile.desktop

import com.letta.mobile.data.plugin.catalog.PluginPackageFile
import com.letta.mobile.data.plugin.catalog.UnavailablePluginCatalog
import com.letta.mobile.data.plugin.catalog.inMemoryLiveViewsSetting
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.plugins.PluginsPageBinding
import com.letta.mobile.ui.shell.pages.plugins.shellPluginsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * letta-mobile-cdgpr.9: the desktop's binding of the shared Plugins page: the platform file dialog
 * for a package, and the catalog. C7 (letta-mobile-cdgpr.7) binds the admin_rpc catalog in place of
 * [UnavailablePluginCatalog]; C8 persists the live-views switch per device. No UI here.
 */
internal fun desktopPluginsPage(): ShellPageContent<ShellDestination.Plugins> = shellPluginsPage(DesktopPluginsBinding)

private val DesktopPluginsBinding = PluginsPageBinding(
    catalog = UnavailablePluginCatalog(),
    pickPackage = ::pickPluginPackage,
    liveViews = inMemoryLiveViewsSetting(),
)

/** The AWT file dialog (modal, on the UI thread), then the chosen file read off it, at most one byte over the page's limit. */
private suspend fun pickPluginPackage(): PluginPackageFile? {
    val dialog = FileDialog(null as Frame?, "Install a plugin package", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = File(dialog.directory, name)
    return withContext(Dispatchers.IO) {
        file.inputStream().use { input -> PluginPackageFile(name, input.readNBytes(MAX_READ_BYTES)) }
    }
}

/** One byte over the page's 16 MiB limit, so the page can say the file is too large. */
private const val MAX_READ_BYTES = 16 * 1024 * 1024 + 1
