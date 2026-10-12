package com.letta.mobile.desktop.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.workspace.directory.AppServerWorkspaceDirectorySource
import com.letta.mobile.data.workspace.directory.DirectoryBrowseException
import com.letta.mobile.data.workspace.directory.FolderCreator
import com.letta.mobile.data.workspace.directory.FolderName
import com.letta.mobile.data.workspace.directory.RecentFolders
import com.letta.mobile.data.workspace.directory.WorkingDirectoryPickerController
import com.letta.mobile.desktop.data.DesktopFileSecureSettingsStore
import com.letta.mobile.desktop.data.defaultDesktopStateDirectory
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import com.letta.mobile.desktop.workspace.DesktopWorkspaceSources
import com.letta.mobile.ui.chat.session.ChatWorkingDirectory
import com.letta.mobile.ui.shell.pages.workspace.WorkingDirectoryPicker
import com.letta.mobile.ui.shell.pages.workspace.WorkingDirectoryPickerHost
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * letta-mobile-zxk2n.6 (G32): the desktop's binding of the shared working-directory picker. It
 * browses through the desktop's workspace route (`list_in_directory` on the direct App Server
 * session) and hands the choice to the conversation's `change_device_state` cwd.
 *
 * With the bundled local runtime the App Server's files are this computer's, so the picker also
 * gets folder creation, "show in file manager" and the OS folder dialog. With a remote App Server
 * those would act on the wrong machine, and letta-code has no create-folder command, so they are
 * left out and the picker says why.
 */
internal class DesktopWorkingDirectoryPicker(
    val controller: WorkingDirectoryPickerController,
    val host: WorkingDirectoryPickerHost,
) {
    fun open(currentCwd: String?) = controller.open(currentCwd)
}

/** The picker for [port]'s conversation (the shared chat page). */
@Composable
internal fun rememberDesktopWorkingDirectoryPicker(port: DesktopChatSessionPort): DesktopWorkingDirectoryPicker =
    rememberDesktopWorkingDirectoryPicker { path -> port.actions.changeWorkingDirectory(ChatWorkingDirectory(path)) }

/** A picker that hands the chosen folder to [onChosen] (the legacy chat pane binds its own). */
@Composable
internal fun rememberDesktopWorkingDirectoryPicker(onChosen: (String) -> Unit): DesktopWorkingDirectoryPicker {
    val latestOnChosen by rememberUpdatedState(onChosen)
    val scope = rememberCoroutineScope()
    // The LOCAL registry holds a client only while the bundled child runs on this computer.
    val localClient by DesktopLocalAppServerClientRegistry.shared.clients.collectAsState(
        DesktopLocalAppServerClientRegistry.shared.currentOrNull(),
    )
    val isLocal = localClient != null
    val recents = remember { RecentFolders(DesktopFileSecureSettingsStore(defaultDesktopStateDirectory().resolve(RECENT_FOLDERS_FILE))) }
    val controller = remember(scope, isLocal) {
        val sources = DesktopWorkspaceSources()
        WorkingDirectoryPickerController(
            source = AppServerWorkspaceDirectorySource(client = sources::client, requestId = sources::requestId),
            scope = scope,
            onChosen = { path -> latestOnChosen(path) },
            recents = recents,
            folderCreator = if (isLocal) DesktopLocalFolderCreator else null,
        )
    }
    DisposableEffect(controller) { onDispose { controller.dismiss() } }
    val systemDialog = rememberDirectoryPickerLauncher(
        dialogSettings = FileKitDialogSettings(title = "Choose working directory"),
    ) { directory -> directory?.let { controller.choose(it.file.absolutePath) } }
    return remember(controller, isLocal, systemDialog) {
        DesktopWorkingDirectoryPicker(
            controller = controller,
            host = WorkingDirectoryPickerHost(
                openInFileManager = if (isLocal) ::showInFileManager else null,
                pickWithSystemDialog = if (isLocal) ({ systemDialog.launch() }) else null,
            ),
        )
    }
}

/** Draws [picker] while it is open. */
@Composable
internal fun DesktopWorkingDirectoryPickerHost(picker: DesktopWorkingDirectoryPicker, forceSheet: Boolean = false) {
    val state by picker.controller.state.collectAsState()
    WorkingDirectoryPicker(
        state = state,
        actions = picker.controller,
        host = if (forceSheet) picker.host.copy(forceSheet = true) else picker.host,
    )
}

/** Creates folders on this computer: only bound while the App Server is the bundled local child. */
internal object DesktopLocalFolderCreator : FolderCreator {
    override suspend fun create(parent: String, name: String): String = withContext(Dispatchers.IO) {
        if (!FolderName(name).isValid) throw DirectoryBrowseException("Folder names cannot contain a slash.")
        val target = try {
            Paths.get(parent).resolve(name.trim())
        } catch (invalid: InvalidPathException) {
            throw DirectoryBrowseException("This folder name is not allowed here.", invalid)
        }
        try {
            Files.createDirectory(target).toAbsolutePath().toString()
        } catch (exists: FileAlreadyExistsException) {
            throw DirectoryBrowseException("A folder with this name already exists.", exists)
        } catch (failed: IOException) {
            throw DirectoryBrowseException("The folder could not be created: ${failed.message ?: "I/O error"}", failed)
        } catch (denied: SecurityException) {
            throw DirectoryBrowseException("The folder could not be created: permission denied.", denied)
        }
    }
}

private fun showInFileManager(path: String) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.OPEN)) return
    // The OS call can block for a moment; never on the UI thread.
    Thread { runCatching { desktop.open(File(path)) } }.apply { isDaemon = true }.start()
}

private const val RECENT_FOLDERS_FILE = "recent-folders.properties"
