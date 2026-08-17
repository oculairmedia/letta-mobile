package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.runtime.LocalRuntimeProviderConfig
import com.letta.mobile.data.runtime.LocalRuntimeProviderStatus
import com.letta.mobile.data.runtime.mergeLocalRuntimeProviderAuth
import com.letta.mobile.data.runtime.readLocalRuntimeProviderStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Desktop-side binding for the shared local-runtime provider config
 * (`com.letta.mobile.data.runtime.LocalRuntimeProviderAuthFile`). This is
 * intentionally just IO: parsing/merge policy lives in `sharedLogic` per
 * the module's cardinal rule.
 *
 * Writes to `<backendDirectory>/providers/auth.json` — the same file and
 * directory the bundled letta-code runtime itself reads
 * (`LETTA_LOCAL_BACKEND_DIR` is set to [backendDirectory] when
 * [DesktopLocalRuntimeHost] spawns the runtime), so a value saved here is
 * exactly what a terminal-driven `letta setup` would have produced.
 */
internal class DesktopLocalRuntimeProviderStore(
    private val backendDirectory: () -> File,
) {
    private fun authFile(): File = File(File(backendDirectory(), "providers"), "auth.json")

    /** Reads the current status without ever surfacing the API key value. */
    fun readStatus(): LocalRuntimeProviderStatus {
        val file = authFile()
        val existing = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        return readLocalRuntimeProviderStatus(existing)
    }

    /**
     * Merges [config] into the existing auth.json (preserving every other
     * provider and unknown field) and writes it atomically: the new
     * content lands in a temp file in the same directory, then that temp
     * file is moved over the target. If anything fails before the move,
     * the existing file — including a missing one — is left untouched.
     */
    fun save(config: LocalRuntimeProviderConfig): Result<LocalRuntimeProviderStatus> = runCatching {
        val file = authFile()
        val existing = if (file.isFile) file.readText() else null
        val merged = mergeLocalRuntimeProviderAuth(
            existingJson = existing,
            config = config,
            nowIso = Instant.now().toString(),
        )
        file.parentFile.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        tempFile.writeText(merged)
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tempFile.delete()
        }
        readLocalRuntimeProviderStatus(merged)
    }

    companion object {
        val default: DesktopLocalRuntimeProviderStore
            get() = DesktopLocalRuntimeProviderStore(DesktopLocalRuntimeHost::backendDirectory)
    }
}
