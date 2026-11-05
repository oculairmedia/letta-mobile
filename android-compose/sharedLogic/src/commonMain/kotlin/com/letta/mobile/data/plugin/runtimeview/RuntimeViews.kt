package com.letta.mobile.data.plugin.runtimeview

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.model.view.RuntimeViewMountId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** An opaque canonical reference. [source] identifies the authority; it does not grant write access. */
@Serializable
data class RuntimeObjectReference(val source: String, val id: String) {
    init { require(source.isNotBlank() && id.isNotBlank()) }
}

/** Read-only canonical content supplied by the host, including fields unknown to this renderer. */
data class RuntimeViewObject(
    val reference: RuntimeObjectReference,
    val schemaId: String,
    val schemaVersion: Int,
    val content: JsonObject,
)

/** Metadata for an already trusted implementation, independent of object identity and mount ownership. */
data class RuntimeViewDescriptor(
    val id: RuntimeViewId,
    val pluginId: String,
    val schemaId: String,
    val schemaVersions: Set<Int>,
    val contractVersion: Int,
) {
    init {
        require(pluginId.isNotBlank() && schemaId.isNotBlank())
        require(schemaVersions.isNotEmpty() && schemaVersions.all { it > 0 })
        require(contractVersion > 0)
    }

    fun supports(value: RuntimeViewObject, binding: RuntimeViewBinding): Boolean =
        contractVersion == RuntimeViewBinding.CONTRACT_VERSION && contractVersion == binding.contractVersion &&
            schemaId == value.schemaId && value.schemaVersion in schemaVersions
}

/** Runtime selection/configuration only. It contains neither executable locations nor capabilities. */
@Serializable
data class RuntimeViewBinding(
    val mountId: RuntimeViewMountId,
    val objectReference: RuntimeObjectReference,
    val viewId: RuntimeViewId,
    val contractVersion: Int,
    val configuration: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(contractVersion > 0)
    }

    companion object {
        const val CONTRACT_VERSION: Int = 1
    }
}

/**
 * Where a mount finds its binding and why it cannot render: the host's [com.letta.mobile.data.plugin.PluginRegistryState],
 * or a client's own store of the bindings it was sent. A view host reads only this, never the whole registry.
 */
interface RuntimeViewBindings {
    fun viewBinding(mountId: RuntimeViewMountId): RuntimeViewBinding?

    /** Validate read-only view inputs without resolving or mutating the canonical object; null renders the view. */
    fun viewFallback(binding: RuntimeViewBinding?, value: RuntimeViewObject?, descriptor: RuntimeViewDescriptor?): RuntimeViewFallback?
}

/** Explicit reasons a mount cannot render its selected implementation. */
enum class RuntimeViewFallback {
    UNBOUND, MISSING_OBJECT, OBJECT_MISMATCH, MISSING_VIEW, UNAVAILABLE_OWNER, INCOMPATIBLE, FAILED,
}
