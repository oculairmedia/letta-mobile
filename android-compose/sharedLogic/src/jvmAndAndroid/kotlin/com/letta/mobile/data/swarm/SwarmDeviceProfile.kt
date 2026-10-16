package com.letta.mobile.data.swarm

import com.letta.mobile.data.controller.node.iroh.IrohNodeIdentity
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.data.pairing.PairedHost
import com.letta.mobile.data.pairing.PairedHostStore
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** The host this device paired with, as stored. No secret: the device authenticates by its key. */
@Serializable
data class PairedHostRecord(
    @SerialName("node_id") val nodeId: String,
    @SerialName("dial_address") val dialAddress: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("paired_at_ms") val pairedAtMs: Long,
) {
    val host: PairedHost get() = PairedHost(nodeId, dialAddress)
}

/** What `unpair` removed: the node id to revoke on the host. */
data class Unpaired(val nodeId: String?, val hostNodeId: String?)

/**
 * letta-mobile-jvctq: one machine's swarm device, all under [directory] (user profile, never the
 * repo): its Iroh identity key (`device.key`), the paired host (`host.json`) and the outbox.
 * The directory and every file in it are readable by the current user only (POSIX 0700/0600, or
 * an owner-only ACL on Windows). The key is never printed or logged.
 */
class SwarmDeviceProfile(val directory: Path) {
    val keyFile: Path = directory.resolve(KEY_FILE)
    private val hostFile: Path = directory.resolve(HOST_FILE)
    val outboxDirectory: Path = directory.resolve(OUTBOX_DIRECTORY)

    /** The device identity: created on first use (pairing), the same key on every later dial. */
    val identity: IrohSecretKeyStore = IrohSecretKeyStore {
        withContext(Dispatchers.IO) {
            ensurePrivateDirectory(directory)
            IrohSecretKeyStores.loadOrCreateFile(keyFile.toString())
        }
    }

    suspend fun host(): PairedHostRecord? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(hostFile) || !Files.isRegularFile(keyFile)) return@withContext null
        LettaJson.persisted.decodeFromString(PairedHostRecord.serializer(), String(Files.readAllBytes(hostFile), Charsets.UTF_8))
    }

    /** Persists the host a redeemed invite named, for [deviceName]; the shared pairing flow calls it only after success. */
    fun hostStore(deviceName: String, nowMs: () -> Long): PairedHostStore = PairedHostStore { host ->
        val record = PairedHostRecord(host.nodeId, host.dialAddress, deviceName, nowMs())
        withContext(Dispatchers.IO) {
            writePrivately(hostFile, LettaJson.persisted.encodeToString(PairedHostRecord.serializer(), record))
        }
    }

    /** This device's NodeId, when it has a key. */
    suspend fun nodeId(): String? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(keyFile)) return@withContext null
        IrohNodeIdentity.nodeIdHexFromSecretBytes(IrohSecretKeyStores.loadOrCreateFile(keyFile.toString()))
    }

    /** Forgets the pairing here (key, host, outbox). The host still lists the device until it is revoked there. */
    suspend fun unpair(): Unpaired {
        val nodeId = nodeId()
        val hostNodeId = runCatching { host()?.nodeId }.getOrNull()
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(keyFile)
            Files.deleteIfExists(hostFile)
            if (Files.isDirectory(outboxDirectory)) {
                Files.list(outboxDirectory).use { files -> files.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(outboxDirectory)
            }
        }
        return Unpaired(nodeId, hostNodeId)
    }

    companion object {
        const val KEY_FILE = "device.key"
        const val HOST_FILE = "host.json"
        const val OUTBOX_DIRECTORY = "outbox"
    }
}

/** Creates [directory] (and parents) readable by the current user only. */
internal fun ensurePrivateDirectory(directory: Path) {
    if (!Files.isDirectory(directory)) Files.createDirectories(directory)
    val posix = runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
    if (posix.isFailure) runCatching { IrohSecretKeyStores.restrictToOwnerAcl(directory) }
}

/** Writes [text] to [target] atomically, with owner-only permissions set before any content lands. */
internal fun writePrivately(target: Path, text: String) {
    val parent = requireNotNull(target.parent) { "no parent directory for $target" }
    ensurePrivateDirectory(parent)
    val temp = parent.resolve(".${target.fileName}.tmp-${UUID.randomUUID()}")
    Files.createFile(temp)
    IrohSecretKeyStores.restrictKeyFilePermissions(temp.toFile())
    Files.write(temp, text.toByteArray(Charsets.UTF_8))
    val moved = runCatching { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    if (moved.isFailure) Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    IrohSecretKeyStores.restrictKeyFilePermissions(target.toFile())
}
