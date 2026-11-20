package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.rpcwire.SecretWriteValue
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialSetCommand
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceSecretChange
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Decodes and validates the methods' params. Every refusal is `service.invalid_request`; nothing here
 * echoes a value, so a malformed secret never reaches an error or a log.
 */
internal object ServiceCredentialParams {
    /** `service.credential.set` params as the write command they carry. */
    fun setCommand(params: JsonObject?): ServiceCredentialSetCommand {
        val fields = params ?: invalid()
        val version = fields["contract_version"]
        if (version != null && (version as? JsonPrimitive)?.intOrNull != ServiceCredentialWire.CONTRACT_VERSION) invalid()
        return ServiceCredentialSetCommand(
            serviceId = serviceId(fields),
            baseUrl = fields.text("base_url")?.trim()?.takeIf { it.isNotEmpty() },
            secret = secretChange(fields),
            config = config(fields["config"]),
            expectedRevision = fields.text("expected_revision"),
        )
    }

    /** The `service_id` of a test. */
    fun testServiceId(params: JsonObject?): ServiceId = serviceId(params ?: invalid())

    /** The `service_id` and `expected_revision` of a removal. */
    fun removal(params: JsonObject?): Pair<ServiceId, String?> {
        val fields = params ?: invalid()
        return serviceId(fields) to fields.text("expected_revision")
    }

    private fun serviceId(fields: JsonObject): ServiceId =
        fields.text("service_id")?.let(::ServiceId)?.takeIf { it.valid } ?: invalid()

    /** At most one of `secret` (store it) and `remove_secret: true`; neither keeps the stored secret. */
    private fun secretChange(fields: JsonObject): ServiceSecretChange {
        val secret = fields.text("secret")
        val remove = fields.flag("remove_secret") == true
        return when {
            secret == null -> if (remove) ServiceSecretChange.Remove else ServiceSecretChange.Keep
            remove || secret.isBlank() || secret.length > ServiceCredentialWire.MAX_SECRET_CHARS -> invalid()
            else -> ServiceSecretChange.Replace(SecretWriteValue(secret.trim()))
        }
    }

    private fun config(element: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (element == null || element == JsonNull) return emptyMap()
        val fields = element as? JsonObject ?: invalid()
        return fields.mapValues { (key, _) -> fields.text(key)?.trim() ?: invalid() }
    }

    /** A string field, null when absent or null; any other type is invalid. */
    private fun JsonObject.text(key: String): String? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    }

    /** A boolean field, null when absent or null; any other type is invalid. */
    private fun JsonObject.flag(key: String): Boolean? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
    }

    private fun invalid(): Nothing = serviceRefuse(ServiceCredentialErrorCode.INVALID_REQUEST)
}
