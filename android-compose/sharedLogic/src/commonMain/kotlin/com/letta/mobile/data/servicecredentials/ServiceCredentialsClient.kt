package com.letta.mobile.data.servicecredentials

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host's answer to one admin RPC: [result] when it succeeded, else its [error] string. */
class ServiceCredentialReply(
    /** Whether the request succeeded. */
    val success: Boolean,
    /** The result; null on failure. */
    val result: JsonElement? = null,
    /** The failure's `error` string; null on success. */
    val error: String? = null,
) {
    override fun toString(): String = "ServiceCredentialReply(success=$success, error=$error)"
}

/**
 * One `admin_rpc` call on the host connection (an Iroh request, as the provider routes are called).
 * The app binds it to its channel transport; tests stand in a fake host.
 */
fun interface ServiceCredentialLink {
    /** Calls [method] with [params]: the host's reply, failed or not. Throws when the link itself fails. */
    suspend fun call(method: String, params: JsonObject): ServiceCredentialReply
}

/**
 * The host's service credentials over [link]: every method is one request, so secrets stay on the
 * host. [hostCapabilities] is what the connected host advertised.
 * Every failure is a [ServiceCredentialException]; nothing here logs, and a secret a write carries is
 * never kept.
 */
class ServiceCredentialsClient(
    private val link: ServiceCredentialLink,
    private val hostCapabilities: () -> Set<String>,
) {
    /** Whether the connected host serves the service-credentials contract. */
    val supported: Boolean get() = ServiceCredentialWire.CAPABILITY in hostCapabilities()

    /** Every known service and whether this device may change them. */
    suspend fun list(): ServiceCredentialList =
        decode(ServiceCredentialList.serializer(), call(ServiceCredentialWire.LIST, JsonObject(emptyMap())))

    /** Stores [command]; the service's new status. */
    suspend fun set(command: ServiceCredentialSetCommand): ServiceCredentialStatus =
        status(ServiceCredentialWire.SET, json.encodeToJsonElement(ServiceCredentialSetCommand.serializer(), command))

    /** Tests the stored credential of [serviceId]; the status with the outcome recorded. */
    suspend fun test(serviceId: ServiceId): ServiceCredentialStatus =
        status(ServiceCredentialWire.TEST, json.encodeToJsonElement(ServiceCredentialTestParams.serializer(), ServiceCredentialTestParams(serviceId)))

    /** Removes everything stored for [serviceId] at [expectedRevision]; the empty status. */
    suspend fun remove(serviceId: ServiceId, expectedRevision: String?): ServiceCredentialStatus {
        val params = ServiceCredentialRemoveParams(serviceId, expectedRevision)
        return status(ServiceCredentialWire.REMOVE, json.encodeToJsonElement(ServiceCredentialRemoveParams.serializer(), params))
    }

    private suspend fun status(method: String, params: JsonElement): ServiceCredentialStatus =
        decode(ServiceCredentialStatus.serializer(), call(method, params as JsonObject))

    private suspend fun call(method: String, params: JsonObject): JsonElement? {
        val reply = link.call(method, params)
        if (!reply.success) throw ServiceCredentialException(ServiceCredentialErrorCode.fromWire(reply.error))
        return reply.result
    }

    private fun <T> decode(serializer: KSerializer<T>, result: JsonElement?): T {
        val element = result ?: throw ServiceCredentialException(ServiceCredentialErrorCode.INTERNAL)
        return runCatching { json.decodeFromJsonElement(serializer, element) }
            .getOrElse { throw ServiceCredentialException(ServiceCredentialErrorCode.INTERNAL) }
    }

    private companion object {
        val json = LettaJson.wire
    }
}
