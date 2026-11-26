package com.letta.mobile.data.node.info

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The wire names and constants of the `node.info` admin_rpc method. */
object NodeInfoWire {
    /** The admin_rpc method a node answers with its [NodeInfo]. */
    const val METHOD: String = "node.info"

    /** The schema of the [NodeInfo] this build writes and reads; a node on another schema is unreadable. */
    const val SCHEMA: Int = 1

    /** The value of a field the node cannot report. */
    const val UNKNOWN: String = "unknown"
}

/**
 * What a node can do, as the epic's role table names it. A node advertises a set of these; roles
 * are only common combinations.
 *
 * @property wireName the capability's name on the wire.
 */
enum class NodeCapability(val wireName: String) {
    /** Runs the shell. */
    UI("ui"),

    /** Holds the replicated catalog. */
    CATALOG("catalog"),

    /** May answer approvals and questions. */
    APPROVE("approve"),

    /** Accepts placement of an agent and runs its turns. */
    EXECUTE("execute"),

    /** Holds full agent state replicas. */
    STORE("store"),

    /** Expected to be reachable at all times. */
    ALWAYS_ON("always-on"),
    ;

    companion object {
        /** The capability named [wireName], or null for a name this build does not know. */
        fun fromWire(wireName: String): NodeCapability? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * The version of the agent store layout a node writes. Nodes whose [major] differs cannot exchange
 * replicas or take over each other's agents; [minor] changes stay readable both ways.
 *
 * @property major bumped when a replica written by one build cannot be materialized by the other.
 * @property minor bumped for additive, readable changes.
 */
@Serializable
data class StoreFormatVersion(val major: Int, val minor: Int) {
    override fun toString(): String = "$major.$minor"

    companion object {
        /** The store layout this build writes: the host-neutral store (uuid conversation ids, LF memory, tokenised paths). */
        val CURRENT: StoreFormatVersion = StoreFormatVersion(major = 1, minor = 0)
    }
}

/**
 * The letta-code runtime patch set a node's App Server applied. Two nodes run the same runtime only
 * when they run the same letta-code version and the same patch set.
 *
 * @property source where the node learned the set: [SOURCE_BOOT_LINE], or [NodeInfoWire.UNKNOWN] when
 *   it has no source (its App Server is supervised elsewhere, or the boot line was not seen yet).
 * @property applied patches the loader applied, when known.
 * @property skipped patches the loader skipped (an anchor that no longer matches), when known.
 * @property digest a stable hash of the applied patch ids, when the node can see them; null otherwise.
 */
@Serializable
data class RuntimePatchSet(
    val source: String = NodeInfoWire.UNKNOWN,
    val applied: Int? = null,
    val skipped: Int? = null,
    @SerialName("patch_ids_sha256") val digest: String? = null,
) {
    /** Whether the node knows its patch set at all. */
    val isKnown: Boolean get() = source != NodeInfoWire.UNKNOWN

    /** A short description: the counts, and the digest's first characters when there is one. */
    val summary: String
        get() = if (!isKnown) {
            NodeInfoWire.UNKNOWN
        } else {
            listOfNotNull(
                applied?.let { "applied=$it" },
                skipped?.let { "skipped=$it" },
                digest?.let { "sha256:${it.take(DIGEST_PREFIX)}" },
            ).joinToString(" ").ifEmpty { NodeInfoWire.UNKNOWN }
        }

    /**
     * Whether this set and [other] are known to differ: by digest when both carry one, else by the
     * applied and skipped counts when both carry them. Null when the two cannot be compared.
     */
    fun differsFrom(other: RuntimePatchSet): Boolean? = when {
        !isKnown || !other.isKnown -> null
        digest != null && other.digest != null -> digest != other.digest
        applied != null && other.applied != null -> applied != other.applied || (skipped ?: 0) != (other.skipped ?: 0)
        else -> null
    }

    companion object {
        /** The source of a set parsed from the patch loader's boot line. */
        const val SOURCE_BOOT_LINE: String = "boot_line"

        /** A node that cannot report its patch set. */
        val UNKNOWN: RuntimePatchSet = RuntimePatchSet()

        private const val DIGEST_PREFIX = 12
    }
}

/**
 * What a node is, as `node.info` returns it. It carries no secrets and no host paths: versions,
 * an OS family, capability names, model handles and the runtime patch set only.
 *
 * @property schema the [NodeInfoWire.SCHEMA] the node wrote.
 * @property os the OS family: windows, linux, macos, android, ios or unknown.
 * @property appVersion the Baken build the node runs.
 * @property lettaCodeVersion the letta-code version its App Server reported.
 * @property storeFormat the agent store layout it writes.
 * @property capabilities the [NodeCapability] wire names it advertises; names this build does not know are kept.
 * @property models the model handles its App Server offers.
 * @property runtimePatches the letta-code runtime patch set its App Server applied.
 */
@Serializable
data class NodeInfo(
    val schema: Int = NodeInfoWire.SCHEMA,
    val os: String = NodeInfoWire.UNKNOWN,
    @SerialName("app_version") val appVersion: String = NodeInfoWire.UNKNOWN,
    @SerialName("letta_code_version") val lettaCodeVersion: String = NodeInfoWire.UNKNOWN,
    @SerialName("store_format") val storeFormat: StoreFormatVersion = StoreFormatVersion.CURRENT,
    val capabilities: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    @SerialName("runtime_patches") val runtimePatches: RuntimePatchSet = RuntimePatchSet.UNKNOWN,
) {
    /** The advertised capabilities this build knows. */
    val capabilitySet: Set<NodeCapability> get() = capabilities.mapNotNullTo(mutableSetOf(), NodeCapability::fromWire)

    /** Whether the node reported its letta-code version. */
    val knowsLettaCodeVersion: Boolean get() = lettaCodeVersion != NodeInfoWire.UNKNOWN
}
