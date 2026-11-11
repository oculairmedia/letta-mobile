package com.letta.mobile.plugin.keystore

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * Who a provider is and what it can do (`describe`; wire `keystore.describe`). It never changes
 * while a provider instance lives.
 */
@Serializable
public data class KeystoreDescriptor(
    /** The provider's id; every [SecretRef] it serves names it. */
    public val id: KeystoreProviderId,
    /** The data operations it serves; any other answers [KeystoreError.Unsupported]. */
    @Serializable(with = CapabilitySetSerializer::class)
    public val capabilities: Set<KeystoreCapability>,
    /** Whether it starts locked and needs `unlock` with host-owned material before data operations. */
    public val needsUnlock: Boolean,
)

/** A data operation a provider may declare. On the wire, its lowercase [wire] name. */
public enum class KeystoreCapability(
    /** The wire spelling. */
    public val wire: String,
) {
    /** `get`. */
    READ("read"),

    /** `put`. */
    WRITE("write"),

    /** `list`. */
    LIST("list"),

    /** `delete`. */
    DELETE("delete"),
    ;

    /** Lookup by wire name. */
    public companion object {
        /** The capability spelled [wire], or null for a name this SPI does not know (a host ignores it). */
        public fun fromWire(wire: String): KeystoreCapability? = entries.firstOrNull { it.wire == wire }
    }
}

/** Every operation a host can ask of a provider, for errors and audit records. */
@Serializable
public enum class KeystoreOperation(
    /** The capability the operation needs, or null when it needs none. */
    public val capability: KeystoreCapability?,
) {
    /** `health`. */
    @SerialName("health")
    HEALTH(null),

    /** `unlock`, which needs [KeystoreDescriptor.needsUnlock]. */
    @SerialName("unlock")
    UNLOCK(null),

    /** `lock`, which needs [KeystoreDescriptor.needsUnlock]. */
    @SerialName("lock")
    LOCK(null),

    /** `get`. */
    @SerialName("get")
    GET(KeystoreCapability.READ),

    /** `put`. */
    @SerialName("put")
    PUT(KeystoreCapability.WRITE),

    /** `list`. */
    @SerialName("list")
    LIST(KeystoreCapability.LIST),

    /** `delete`. */
    @SerialName("delete")
    DELETE(KeystoreCapability.DELETE),
}

/** Whether a provider's data operations can succeed now (`health`; wire `keystore.health`). */
@Serializable
public data class KeystoreHealth(
    /** The provider's state. */
    public val status: HealthStatus,
    /** Optional operator text, at most [KeystoreSpi.MAX_DIAGNOSTIC_LENGTH] characters, never a value. */
    public val detail: ProviderDiagnostic? = null,
)

/** A provider's state as [KeystoreHealth] reports it. */
@Serializable
public enum class HealthStatus {
    /** Data operations can succeed. */
    @SerialName("ready")
    READY,

    /** The provider needs `unlock`; data operations answer [KeystoreError.Locked]. */
    @SerialName("locked")
    LOCKED,

    /** The backend is unreachable or unhealthy; data operations answer [KeystoreError.Unavailable]. */
    @SerialName("unavailable")
    UNAVAILABLE,
}

/** Non-secret notes a `put` may carry; never returned by `get` in contract v1 and never holding a value. */
@Serializable
public data class SecretMetadata(
    /** What the secret is for, in words for its owner. */
    public val description: SecretDescription? = null,
)

/** Owner-facing text about a secret, at most [KeystoreSpi.MAX_DESCRIPTION_LENGTH] characters; never a value. */
@Serializable
@JvmInline
public value class SecretDescription(
    /** The description text. */
    public val value: String,
) {
    /** Whether [value] is within the length limit. */
    public val isWellFormed: Boolean get() = value.length <= KeystoreSpi.MAX_DESCRIPTION_LENGTH

    /** The description text itself. */
    override fun toString(): String = value
}

/**
 * Operator text a provider attaches to a health report or failure, at most
 * [KeystoreSpi.MAX_DIAGNOSTIC_LENGTH] characters. It must never contain a value; a host drops a
 * diagnostic holding any value it knows.
 */
@Serializable
@JvmInline
public value class ProviderDiagnostic(
    /** The diagnostic text. */
    public val value: String,
) {
    /** Whether [value] is within the length limit. */
    public val isWellFormed: Boolean get() = value.length <= KeystoreSpi.MAX_DIAGNOSTIC_LENGTH

    /** The diagnostic text itself. */
    override fun toString(): String = value
}

/** Capabilities as a JSON array of wire names; names this SPI does not know are dropped on decode. */
internal object CapabilitySetSerializer : KSerializer<Set<KeystoreCapability>> {
    private val names = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(encoder: Encoder, value: Set<KeystoreCapability>) {
        names.serialize(encoder, value.map(KeystoreCapability::wire))
    }

    override fun deserialize(decoder: Decoder): Set<KeystoreCapability> =
        names.deserialize(decoder).mapNotNull(KeystoreCapability::fromWire).toSet()
}
