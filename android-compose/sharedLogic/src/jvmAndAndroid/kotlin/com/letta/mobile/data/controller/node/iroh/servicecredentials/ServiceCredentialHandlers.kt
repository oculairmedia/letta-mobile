package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.controller.node.iroh.hostWireJson
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialList
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import com.letta.mobile.data.servicecredentials.ServiceTestState
import com.letta.mobile.data.servicecredentials.ServiceTestStatus
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `service.credential.list`, `.set`, `.test` and `.remove`. Writes never contact a service; a test
 * sends only its adapter's fixed synthetic request and records the outcome against the revision it
 * tested. An upstream failure is a test outcome, not a refusal.
 */
internal class ServiceCredentialHandlers(
    private val store: ServiceCredentialStore,
    private val clock: () -> Long,
) {
    /** Every service's status, and whether the caller's pairing may change them. */
    suspend fun list(context: AdminRpcRequestContext): JsonElement {
        val required = IrohPeerCapabilities.forAdminMethod(ServiceCredentialWire.SET)
        val list = ServiceCredentialList(
            canManage = IrohPeerCapabilities.isAllowed(context.grantedCapabilities, required),
            services = store.list(),
        )
        return hostWireJson(ServiceCredentialList.serializer(), list)
    }

    /** Writes one service's settings and returns its new status. */
    suspend fun set(params: JsonObject?): JsonElement = store.write(ServiceCredentialParams.setCommand(params)).toWire()

    /** Removes one service's settings and secret and returns its empty status. */
    suspend fun remove(params: JsonObject?): JsonElement {
        val (serviceId, expectedRevision) = ServiceCredentialParams.removal(params)
        return store.remove(serviceId, expectedRevision).toWire()
    }

    /** Tests one service's stored credential and returns its status with the outcome recorded. */
    suspend fun test(params: JsonObject?): JsonElement {
        val serviceId = ServiceCredentialParams.testServiceId(params)
        val adapter = store.adapter(serviceId)
        val snapshot = store.snapshot(serviceId)
        val failure = try {
            adapter.test(snapshot.target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            ServiceCredentialErrorCode.UPSTREAM_ERROR
        }
        val outcome = ServiceTestStatus(
            state = if (failure == null) ServiceTestState.PASSED else ServiceTestState.FAILED,
            checkedAtMs = clock(),
            error = failure,
        )
        return store.recordTest(serviceId, snapshot.revision, outcome).toWire()
    }

    private fun ServiceCredentialStatus.toWire(): JsonElement = hostWireJson(ServiceCredentialStatus.serializer(), this)
}
