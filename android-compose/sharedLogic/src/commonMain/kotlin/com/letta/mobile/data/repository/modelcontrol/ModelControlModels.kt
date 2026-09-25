package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.LlmModel

/**
 * Client-side domain for App Server provider management and model exposure
 * (letta-mobile-w4q4p). Decoded from the wrapper's `provider.*` / `model.*`
 * admin RPC by [ModelControlWire]; platform-neutral.
 */

/** A model's selection handle (e.g. `openai/gpt-sol`); the key of exposure decisions. */
@kotlin.jvm.JvmInline
value class ModelHandle(val value: String) {
    override fun toString(): String = value
}

/** A connectable provider row id from `provider.list` (e.g. `openai-compatible`). */
@kotlin.jvm.JvmInline
value class ConnectableProviderId(val value: String) {
    override fun toString(): String = value
}

/** Which provider (and, for multi-alias rows, which connected alias) to disconnect. */
data class ProviderDisconnectTarget(val providerId: ConnectableProviderId, val providerName: String? = null)

/** One exposure decision for `model.exposure.set`. */
data class ExposureChange(val handle: ModelHandle, val exposed: Boolean)

/** One input of a provider connect form (`ConnectProviderField`). */
data class ProviderField(
    val key: String,
    val label: String,
    val placeholder: String? = null,
    val secret: Boolean = false,
    val required: Boolean = false,
)

/**
 * One way to authenticate a provider. Providers that declare only top-level
 * `fields` get a single method with a null [id] (sent as no `auth_method_id`).
 */
data class ProviderAuthMethod(
    val id: String?,
    val label: String,
    val description: String = "",
    val fields: List<ProviderField>,
)

/** A connected alias of a provider row (`ConnectProviderConnectionState`). */
data class ProviderConnection(
    val providerName: String?,
    val providerType: String?,
    val authType: String?,
    val baseUrl: String?,
)

data class ConnectableProvider(
    val id: String,
    val displayName: String,
    val description: String,
    val providerType: String,
    val providerName: String,
    val isOauth: Boolean,
    val requiresApiKey: Boolean,
    val authMethods: List<ProviderAuthMethod>,
    val connections: List<ProviderConnection>,
) {
    val isConnected: Boolean get() = connections.isNotEmpty()

    /** OAuth rows need a device/browser login flow the apps do not run yet. */
    val canConnectFromApp: Boolean get() = !isOauth && authMethods.isNotEmpty()
}

/** Result of `provider.connect` / `provider.disconnect`. */
data class ProviderMutationResult(
    val providers: List<ConnectableProvider>,
    val modelsMayHaveChanged: Boolean,
)

/** A `model.list` row with the wrapper's exposure decision and reasoning variants. */
data class CatalogModel(
    val model: LlmModel,
    val exposed: Boolean,
    val reasoningEfforts: List<String>,
) {
    /** Exposure is keyed by the selection handle the wrapper projected. */
    val handle: ModelHandle get() = ModelHandle(model.handle ?: model.id)
}

/** What `model.update` should do with the reasoning effort. */
sealed interface ReasoningEffortChoice {
    /** Leave the conversation's current effort alone (key omitted). */
    data object Unchanged : ReasoningEffortChoice

    /** Restore the provider default (explicit `null`). */
    data object ProviderDefault : ReasoningEffortChoice

    data class Named(val effort: String) : ReasoningEffortChoice
}

/** Result of `model.update`. */
data class ConversationModelUpdate(
    val appliedTo: String?,
    val modelHandle: String?,
)
