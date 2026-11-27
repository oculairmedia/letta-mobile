package com.letta.mobile.data.node.info

/** Why a node cannot be used for some of what it advertises. */
enum class NodeIncompatibilityKind(
    /** What the reason takes away. */
    val blocks: Set<NodeCapability>,
) {
    /** The node writes a `node.info` schema this build cannot read: nothing it says can be trusted. */
    SCHEMA(NodeCapability.entries.toSet()),

    /** The node writes another major store format: its replicas and agents cannot cross. */
    STORE_FORMAT(setOf(NodeCapability.EXECUTE, NodeCapability.STORE)),

    /** The node runs another letta-code version than the reference node: agents behave differently there. */
    LETTA_CODE(setOf(NodeCapability.EXECUTE, NodeCapability.STORE)),

    /** The node applied another runtime patch set than the reference node. */
    RUNTIME_PATCHES(setOf(NodeCapability.EXECUTE, NodeCapability.STORE)),
}

/**
 * One reason, with both sides' values so a person can act on it.
 *
 * @property expected the value this side expects (this build's, or the reference node's).
 * @property actual the candidate node's value.
 */
data class NodeIncompatibility(
    val kind: NodeIncompatibilityKind,
    val expected: String,
    val actual: String,
)

/** How usable a node is overall. */
enum class NodeAvailability {
    /** Everything it advertises can be used. */
    COMPATIBLE,

    /** It can be browsed (ui, catalog) but some of what it advertises is blocked. */
    LIMITED,

    /** Nothing it says can be used. */
    UNAVAILABLE,

    /** It predates `node.info`, so nothing was checked and nothing is blocked. */
    UNVERIFIED,
}

/**
 * The verdict on one node: what it reported and every reason it cannot be used for something.
 *
 * @property info what the node reported; null for a node that predates `node.info`.
 * @property reasons why parts of it are blocked, empty when nothing is.
 */
data class NodeCompatibility(
    val info: NodeInfo?,
    val reasons: List<NodeIncompatibility>,
) {
    /** Capabilities some reason blocks. */
    val blocked: Set<NodeCapability> get() = reasons.flatMapTo(mutableSetOf()) { it.kind.blocks }

    /** The overall availability. */
    val availability: NodeAvailability
        get() = when {
            info == null -> NodeAvailability.UNVERIFIED
            reasons.isEmpty() -> NodeAvailability.COMPATIBLE
            NodeCapability.CATALOG in blocked || NodeCapability.UI in blocked -> NodeAvailability.UNAVAILABLE
            else -> NodeAvailability.LIMITED
        }

    /** Whether [capability] may be used on this node: a legacy node is trusted as before, a checked one must advertise it. */
    fun allows(capability: NodeCapability): Boolean =
        capability !in blocked && (info == null || capability in info.capabilitySet)

    companion object {
        /** A node that does not answer `node.info`: unchecked, and blocked for nothing. */
        val LEGACY: NodeCompatibility = NodeCompatibility(info = null, reasons = emptyList())
    }
}

/**
 * The compatibility rule, as a pure function. A node is judged against this build (its store
 * format and `node.info` schema) and, when there is one, a reference node (the node in this process,
 * or the node an agent would move from), whose letta-code version and runtime patch set it must match.
 *
 * - Another `node.info` schema blocks everything.
 * - Another store-format major, letta-code version or patch set blocks execute and store; the node
 *   stays usable for ui and catalog.
 * - A value either side reports as unknown is not a mismatch: it cannot be checked yet.
 */
object NodeCompatibilityRule {
    /** The verdict on [candidate], against [localStoreFormat] and the optional [reference] node. */
    fun evaluate(
        candidate: NodeInfo,
        reference: NodeInfo? = null,
        localStoreFormat: StoreFormatVersion = StoreFormatVersion.CURRENT,
    ): NodeCompatibility {
        if (candidate.schema != NodeInfoWire.SCHEMA) {
            return NodeCompatibility(
                candidate,
                listOf(NodeIncompatibility(NodeIncompatibilityKind.SCHEMA, NodeInfoWire.SCHEMA.toString(), candidate.schema.toString())),
            )
        }
        val reasons = buildList {
            if (candidate.storeFormat.major != localStoreFormat.major) {
                add(
                    NodeIncompatibility(
                        NodeIncompatibilityKind.STORE_FORMAT,
                        localStoreFormat.toString(),
                        candidate.storeFormat.toString(),
                    ),
                )
            }
            if (reference != null) addAll(runtimeParity(reference, candidate))
        }
        return NodeCompatibility(candidate, reasons)
    }

    /** The reference and the candidate run the same letta-code and the same patch set, where both know. */
    private fun runtimeParity(reference: NodeInfo, candidate: NodeInfo): List<NodeIncompatibility> = buildList {
        if (reference.knowsLettaCodeVersion && candidate.knowsLettaCodeVersion &&
            reference.lettaCodeVersion != candidate.lettaCodeVersion
        ) {
            add(NodeIncompatibility(NodeIncompatibilityKind.LETTA_CODE, reference.lettaCodeVersion, candidate.lettaCodeVersion))
        }
        if (reference.runtimePatches.differsFrom(candidate.runtimePatches) == true) {
            add(
                NodeIncompatibility(
                    NodeIncompatibilityKind.RUNTIME_PATCHES,
                    reference.runtimePatches.summary,
                    candidate.runtimePatches.summary,
                ),
            )
        }
    }
}
