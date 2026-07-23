package com.letta.mobile.desktop.security

import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Stable desktop Iroh client identity (d6e8g.4). The 32-byte private key is
 * stored vault-encrypted (DPAPI on Windows, keyfile-AES-GCM elsewhere) so the
 * desktop dials with the SAME NodeId across restarts and updates — the
 * prerequisite for NodeId-bound pairing (d6e8g.5) — instead of minting a fresh
 * ephemeral identity per process. [reset] deliberately discards the identity;
 * the next load mints a new one (and any server-side pairing must be redone).
 */
class DesktopIrohIdentityStore(
    stateDirectory: Path,
    private val vault: DesktopSecretVault,
) {
    private val path: Path = stateDirectory.resolve(FILE_NAME)

    @Synchronized
    fun loadOrCreate(): ByteArray {
        if (Files.exists(path)) {
            val key = vault.unprotect(Files.readAllBytes(path))
            IrohSecretKeyStores.requireValidKey(key)
            return key
        }
        val key = IrohSecretKeyStores.generateKeyBytes()
        writeAtomically(vault.protect(key))
        return key
    }

    /** Deliberate identity reset: the next [loadOrCreate] mints a new NodeId. */
    @Synchronized
    fun reset() {
        Files.deleteIfExists(path)
    }

    private fun writeAtomically(blob: ByteArray) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
        Files.write(temp, blob)
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FILE_NAME = "iroh-client-identity.enc"
    }
}

/** Process-wide default identity, rooted in the desktop state directory. */
object DesktopIrohIdentity {
    private val store by lazy {
        val stateDirectory = java.nio.file.Path.of(System.getProperty("user.home"), ".letta-mobile")
        DesktopIrohIdentityStore(stateDirectory, DesktopSecretVaults.forCurrentOs(stateDirectory))
    }

    fun loadOrCreate(): ByteArray = store.loadOrCreate()

    fun reset() = store.reset()
}
