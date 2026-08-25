package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.HostId
import kotlinx.serialization.Serializable

/**
 * Granular capability grants for the provider_management_v1 RPC protocol.
 */
@Serializable
sealed interface ProviderManagementCapability {
    val wireName: String

    data object Read : ProviderManagementCapability {
        override val wireName: String = "provider_management:read"
    }

    data object Write : ProviderManagementCapability {
        override val wireName: String = "provider_management:write"
    }

    data class Unknown(val raw: String) : ProviderManagementCapability {
        override val wireName: String get() = raw
    }

    companion object {
        fun fromWire(raw: String): ProviderManagementCapability = when (raw.trim().lowercase()) {
            "provider_management:read", "provider_management_read", "read" -> Read
            "provider_management:write", "provider_management_write", "write" -> Write
            else -> Unknown(raw)
        }
    }
}

/**
 * Authentication and capability context for an inbound Admin RPC request.
 */
data class ProviderRpcAuthContext(
    val activeHostId: HostId,
    val peerId: String? = null,
    val grantedCapabilities: Set<ProviderManagementCapability> = emptySet(),
) {
    val isAuthenticated: Boolean get() = !peerId.isNullOrBlank()

    fun hasCapability(capability: ProviderManagementCapability): Boolean =
        grantedCapabilities.contains(capability)
}
