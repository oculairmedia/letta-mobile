package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.host.HostVoiceGrants
import com.letta.mobile.data.voice.host.HostVoiceReachability
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The stored speech setup, without its key: the server, the names used on it, the grants and the last check. */
@Serializable
internal data class StoredVoiceSetup(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("transcription_model") val transcriptionModel: String? = null,
    @SerialName("speech_model") val speechModel: String? = null,
    @SerialName("voice") val voice: String? = null,
    @SerialName("language") val language: String? = null,
    @SerialName("grants") val grants: HostVoiceGrants = HostVoiceGrants(),
    @SerialName("granted_at_ms") val grantedAtMs: Long? = null,
    @SerialName("reachability") val reachability: HostVoiceReachability = HostVoiceReachability(),
) {
    /** The origin the grants and the key are bound to. */
    val origin: String get() = originOf(baseUrl).orEmpty()
}

/**
 * Everything the setup file holds: the last revision number ever issued, which keeps counting across a
 * clear so an old `expected_revision` never matches a newer setup, and the setup itself.
 */
@Serializable
internal data class VoiceSetupState(
    @SerialName("revision") val revision: Long = 0,
    @SerialName("setup") val setup: StoredVoiceSetup? = null,
)

/** The origin (scheme, host and port) of [baseUrl], or null when it is not an http(s) URL. */
internal fun originOf(baseUrl: String): String? = EndpointOrigin.of(SpeechEndpointUrl(baseUrl))?.value

/** Where the setup (without its key) is kept. */
internal interface VoiceSetupPersistence {
    /** The stored state; empty when nothing was ever stored. */
    fun read(): VoiceSetupState

    /** Replaces the stored state. */
    fun write(state: VoiceSetupState)
}

/**
 * The host's store for the speech server key. A key is stored together with the origin it was entered
 * for and is only ever returned for that origin, so a setup that moved to another server can never hand
 * the old key to it, even if the setup file and the key file disagree after a crash.
 */
internal interface VoiceKeyStore {
    /** The key stored for [origin], or null. */
    fun read(origin: String): String?

    /** Stores [key] for [origin], replacing any stored key. */
    fun write(origin: String, key: String)

    /** Deletes the stored key. */
    fun delete()
}

/** The setup in memory: tests, and hosts without a state directory. */
internal class InMemoryVoiceSetupPersistence(private var state: VoiceSetupState = VoiceSetupState()) : VoiceSetupPersistence {
    override fun read(): VoiceSetupState = state

    override fun write(state: VoiceSetupState) {
        this.state = state
    }
}

/** A key in memory: tests only. */
internal class InMemoryVoiceKeyStore : VoiceKeyStore {
    private var stored: Pair<String, String>? = null

    override fun read(origin: String): String? = stored?.takeIf { it.first == origin }?.second

    override fun write(origin: String, key: String) {
        stored = origin to key
    }

    override fun delete() {
        stored = null
    }

    override fun toString(): String = "InMemoryVoiceKeyStore(keySet=${stored != null})"
}

/** The setup as a small JSON file, replaced atomically. Holds no key. */
internal class FileVoiceSetupPersistence(private val path: Path) : VoiceSetupPersistence {
    override fun read(): VoiceSetupState =
        AtomicJsonFile.readOrNull(path)?.let { storeJson.decodeFromString(VoiceSetupState.serializer(), it) } ?: VoiceSetupState()

    override fun write(state: VoiceSetupState) {
        AtomicJsonFile.write(path, storeJson.encodeToString(VoiceSetupState.serializer(), state))
    }
}

/**
 * The key in a file of its own that only the host's user may read (POSIX `rw-------`, or an owner-only
 * ACL on Windows), the way the host keeps its Iroh identity key. The file is restricted before the key
 * is written into it, and replaced by a rename.
 */
internal class FileVoiceKeyStore(private val path: Path) : VoiceKeyStore {
    override fun read(origin: String): String? {
        val text = AtomicJsonFile.readOrNull(path) ?: return null
        val stored = runCatching { storeJson.decodeFromString(StoredKey.serializer(), text) }.getOrNull() ?: return null
        return stored.key.takeIf { stored.origin == origin }
    }

    override fun write(origin: String, key: String) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
        try {
            Files.createFile(temp)
            IrohSecretKeyStores.restrictKeyFilePermissions(temp.toFile())
            Files.write(temp, storeJson.encodeToString(StoredKey.serializer(), StoredKey(origin, key)).toByteArray(Charsets.UTF_8))
            runCatching { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
            IrohSecretKeyStores.restrictKeyFilePermissions(path.toFile())
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun delete() {
        Files.deleteIfExists(path)
    }

    override fun toString(): String = "FileVoiceKeyStore"

    @Serializable
    private class StoredKey(
        @SerialName("origin") val origin: String,
        @SerialName("key") val key: String,
    )
}

private val storeJson = LettaJson.persisted
