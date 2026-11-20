package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.servicecredentials.ServiceConfigField
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceId

/**
 * What a service is and how its form is built: the data half of adding a service. The other half is
 * its [ServiceAdapter].
 */
internal class ServiceDescriptor(
    /** The service. */
    val id: ServiceId,
    /** The name people see. */
    val displayName: String,
    /** One line saying what the system uses it for. */
    val summary: String,
    /** The address used when none is stored; null when the service has none. */
    val defaultBaseUrl: String?,
    /** Whether the address may be changed. */
    val baseUrlEditable: Boolean,
    /** What the secret is called on the form. */
    val secretLabel: String,
    /** The service's own settings. */
    val configFields: List<ServiceConfigField> = emptyList(),
) {
    init {
        require(id.valid) { "Invalid service id" }
        require(defaultBaseUrl != null || baseUrlEditable) { "A service without a default address must let it be set" }
    }

    /** The setting [key] declares, or null. */
    fun field(key: String): ServiceConfigField? = configFields.firstOrNull { it.key == key }
}

/** Where one test goes: the address, the secret and the settings. Prints no secret or address. */
internal class ServiceTarget(
    /** The effective address. */
    val baseUrl: String,
    /** The stored secret. */
    val secret: String,
    /** The stored settings, defaults filled in. */
    val config: Map<String, String>,
) {
    override fun toString(): String = "ServiceTarget(configKeys=${config.keys})"
}

/**
 * One service's adapter: its [descriptor] and its connection test. A test sends only a fixed
 * synthetic request compiled into the adapter, never caller text, and reports a test outcome code or
 * null when the service accepted the credential. It never throws for an upstream failure.
 */
internal interface ServiceAdapter {
    /** The service it serves. */
    val descriptor: ServiceDescriptor

    /** Tests [target]; null when the service accepted it. */
    suspend fun test(target: ServiceTarget): ServiceCredentialErrorCode?
}

/** A refusal with one code of the contract's closed set; the route answers exactly its wire string. */
internal class ServiceRefusal(val code: ServiceCredentialErrorCode) : Exception(code.wire)

/** Refuses the request with [code]. */
internal fun serviceRefuse(code: ServiceCredentialErrorCode): Nothing = throw ServiceRefusal(code)
