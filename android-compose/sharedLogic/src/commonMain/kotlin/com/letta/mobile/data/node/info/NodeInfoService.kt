package com.letta.mobile.data.node.info

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What the host knows about its node before any App Server answers: plain facts it is configured
 * with, and where it reads the runtime patch set.
 *
 * @property os the OS family token ([NodeOs.family]).
 * @property appVersion the Baken build version.
 * @property capabilities what the node advertises.
 * @property runtimePatches where the node reads its App Server's applied patch set.
 */
class NodeFacts(
    val os: String = NodeInfoWire.UNKNOWN,
    val appVersion: String = NodeInfoWire.UNKNOWN,
    val capabilities: Set<NodeCapability> = emptySet(),
    val runtimePatches: RuntimePatchSetSource = RuntimePatchSetSource.Unknown,
) {
    companion object {
        /** A node that was configured with nothing: every field reads unknown. */
        val UNKNOWN: NodeFacts = NodeFacts()
    }
}

/** What only the node's live App Server can say. Each read may fail; the service then reports unknown. */
interface NodeRuntimeFacts {
    /** The letta-code version the App Server reported, or null before it did. */
    fun lettaCodeVersion(): String?

    /** The model handles the App Server offers. */
    suspend fun models(): List<String>

    companion object {
        /** A node without an App Server. */
        val None: NodeRuntimeFacts = of(lettaCodeVersion = { null }, models = { emptyList() })

        /** Runtime facts read through [lettaCodeVersion] and [models]. */
        fun of(lettaCodeVersion: () -> String?, models: suspend () -> List<String>): NodeRuntimeFacts = object : NodeRuntimeFacts {
            override fun lettaCodeVersion(): String? = lettaCodeVersion()

            override suspend fun models(): List<String> = models()
        }
    }
}

/** The OS family a node reports: never a version, a hostname or anything else host-specific. */
object NodeOs {
    /** The family of a JVM `os.name` (or any OS name), or [NodeInfoWire.UNKNOWN]. */
    fun family(osName: String?): String {
        val name = osName?.lowercase().orEmpty()
        return when {
            "android" in name -> "android"
            "windows" in name -> "windows"
            "mac" in name || "darwin" in name -> "macos"
            "ios" in name -> "ios"
            "linux" in name -> "linux"
            else -> NodeInfoWire.UNKNOWN
        }
    }
}

/**
 * Builds the node's [NodeInfo] from its [facts] and its live [runtime]. Every free-text value is
 * reduced to a version-like token, so neither a secret nor a host path can reach the wire.
 */
class NodeInfoService(
    private val facts: NodeFacts,
    private val runtime: NodeRuntimeFacts = NodeRuntimeFacts.None,
) {
    /** The node as it is now. Never throws for a missing or failing App Server: those fields read unknown or empty. */
    suspend fun read(): NodeInfo = NodeInfo(
        os = token(facts.os),
        appVersion = token(facts.appVersion),
        lettaCodeVersion = token(runCatching { runtime.lettaCodeVersion() }.getOrNull()),
        storeFormat = StoreFormatVersion.CURRENT,
        capabilities = NodeCapability.entries.filter { it in facts.capabilities }.map { it.wireName },
        models = models(),
        runtimePatches = patches(),
    )

    /** [read] as the admin_rpc result element, every field present. */
    suspend fun readJson(): JsonElement = WIRE.encodeToJsonElement(NodeInfo.serializer(), read())

    private suspend fun models(): List<String> = try {
        runtime.models().filter(::isModelHandle).distinct()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun patches(): RuntimePatchSet {
        val set = runCatching { facts.runtimePatches.current() }.getOrNull() ?: return RuntimePatchSet.UNKNOWN
        if (!set.isKnown) return RuntimePatchSet.UNKNOWN
        return set.copy(digest = set.digest?.takeIf { HEX.matches(it) })
    }

    private companion object {
        val WIRE = Json { encodeDefaults = true; explicitNulls = true }
        val TOKEN = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,63}""")
        val HEX = Regex("""[0-9a-f]{64}""")
        val MODEL_HANDLE = Regex("""[A-Za-z0-9][A-Za-z0-9._:@+/-]{0,199}""")
        val DRIVE_LETTER = Regex("""^[A-Za-z]:""")

        fun token(value: String?): String = value?.trim()?.takeIf { TOKEN.matches(it) } ?: NodeInfoWire.UNKNOWN

        /** A provider/model handle: no leading slash, no backslash, no drive letter, no parent segment. */
        fun isModelHandle(handle: String): Boolean =
            MODEL_HANDLE.matches(handle) && ".." !in handle && !DRIVE_LETTER.containsMatchIn(handle)
    }
}
