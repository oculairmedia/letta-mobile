package com.letta.mobile.data.servicecredentials

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Names, capability and limits of the service-credentials v1 contract, shared by host and client. */
object ServiceCredentialWire {
    /** The contract version every list, status and write carries. */
    const val CONTRACT_VERSION: Int = 1

    /** Advertised by a host that serves every v1 method; without it a client offers no Services page. */
    const val CAPABILITY: String = "service_credentials_v1"

    /** Lists every service the host knows with its status, never a secret. */
    const val LIST: String = "service.credential.list"

    /** Stores or replaces one service's settings and secret. */
    const val SET: String = "service.credential.set"

    /** Checks one service's stored credential with a fixed synthetic request and records the outcome. */
    const val TEST: String = "service.credential.test"

    /** Deletes one service's settings, secret and last test. */
    const val REMOVE: String = "service.credential.remove"

    /** Every v1 method. */
    val METHODS: Set<String> = setOf(LIST, SET, TEST, REMOVE)

    /** Longest secret accepted, in characters. */
    const val MAX_SECRET_CHARS: Int = 4_096

    /** Longest config value accepted, in characters. */
    const val MAX_CONFIG_VALUE_CHARS: Int = 256
}

/**
 * One service the host keeps a credential for (`typesafe-jev`): lowercase letters and digits in
 * dash-separated words, at most 64 characters. It names a slot, never a value.
 */
@JvmInline
@Serializable
value class ServiceId(val value: String) {
    /** Whether [value] follows the id grammar. */
    val valid: Boolean get() = value.length <= MAX_LENGTH && GRAMMAR.matches(value)

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 64
        val GRAMMAR = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}
