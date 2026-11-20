package com.letta.mobile.data.servicecredentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `service.credential.list`: every service the host knows, and whether this device may change them. */
@Serializable
data class ServiceCredentialList(
    /** The contract version. */
    @SerialName("contract_version") val contractVersion: Int = ServiceCredentialWire.CONTRACT_VERSION,
    /** Whether this device's pairing may set, test and remove credentials (`admin.full`). */
    @SerialName("can_manage") val canManage: Boolean = false,
    /** One status per known service, in the host's order. */
    @SerialName("services") val services: List<ServiceCredentialStatus> = emptyList(),
)

/**
 * One service as the host reports it: what it is, how its form is built ([configFields],
 * [baseUrlEditable], [secretLabel]) and what is stored. It never carries the secret, only
 * [hasSecret].
 */
@Serializable
data class ServiceCredentialStatus(
    /** The service. */
    @SerialName("service_id") val serviceId: ServiceId,
    /** The name people see. */
    @SerialName("display_name") val displayName: String,
    /** One line saying what the system uses the service for. */
    @SerialName("summary") val summary: String = "",
    /** Whether anything is stored for the service. */
    @SerialName("configured") val configured: Boolean = false,
    /** The address used when none is stored; null when the service has no fixed address. */
    @SerialName("default_base_url") val defaultBaseUrl: String? = null,
    /** The stored address; null uses [defaultBaseUrl]. */
    @SerialName("base_url") val baseUrl: String? = null,
    /** Whether the address may be changed. */
    @SerialName("base_url_editable") val baseUrlEditable: Boolean = false,
    /** Whether the host keeps a secret for the service. */
    @SerialName("has_secret") val hasSecret: Boolean = false,
    /** What the secret is called on the form ("API key"). */
    @SerialName("secret_label") val secretLabel: String = "",
    /** The service's own settings the form offers. */
    @SerialName("config_fields") val configFields: List<ServiceConfigField> = emptyList(),
    /** The stored settings, by [ServiceConfigField.key]. */
    @SerialName("config") val config: Map<String, String> = emptyMap(),
    /** The stored revision a write may name; null when nothing is stored. */
    @SerialName("revision") val revision: String? = null,
    /** The last connection test of the stored credential. */
    @SerialName("last_test") val lastTest: ServiceTestStatus = ServiceTestStatus(),
)

/** One setting of a service's form. */
@Serializable
data class ServiceConfigField(
    /** The setting's key in [ServiceCredentialStatus.config]. */
    @SerialName("key") val key: String,
    /** Its label. */
    @SerialName("label") val label: String,
    /** A line of help under it. */
    @SerialName("help") val help: String = "",
    /** The value used when none is stored. */
    @SerialName("default") val defaultValue: String? = null,
)

/** How the last connection test of a stored credential ended. */
@Serializable
enum class ServiceTestState {
    /** Never tested since the last write. */
    @SerialName("unknown")
    UNKNOWN,

    /** The service accepted the credential. */
    @SerialName("passed")
    PASSED,

    /** The test failed; [ServiceTestStatus.error] says why. */
    @SerialName("failed")
    FAILED,
}

/** The last test: its state, when it ran, and why it failed. */
@Serializable
data class ServiceTestStatus(
    /** How it ended. */
    @SerialName("state") val state: ServiceTestState = ServiceTestState.UNKNOWN,
    /** When it ran, in epoch milliseconds; null when never. */
    @SerialName("checked_at_ms") val checkedAtMs: Long? = null,
    /** Why it failed; null unless [state] is failed. */
    @SerialName("error") val error: ServiceCredentialErrorCode? = null,
)
