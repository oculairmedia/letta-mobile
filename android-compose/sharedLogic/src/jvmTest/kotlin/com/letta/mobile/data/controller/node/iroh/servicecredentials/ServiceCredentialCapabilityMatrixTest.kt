package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.IrohPeerCapabilities
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** The capability each service credential method needs, and what the routes register and advertise. */
class ServiceCredentialCapabilityMatrixTest {
    @Test
    fun theListIsAChatReadAndWritesAreAdmin() {
        val expected = mapOf(
            ServiceCredentialWire.LIST to IrohPeerCapabilities.CHAT_READ,
            ServiceCredentialWire.SET to IrohPeerCapabilities.ADMIN_FULL,
            ServiceCredentialWire.TEST to IrohPeerCapabilities.ADMIN_FULL,
            ServiceCredentialWire.REMOVE to IrohPeerCapabilities.ADMIN_FULL,
        )
        assertEquals(expected, ServiceCredentialWire.METHODS.associateWith(IrohPeerCapabilities::forAdminMethod))
    }

    @Test
    fun theRoutesRegisterEveryMethodAndAdvertiseTheContract() {
        val router = AdminRpcRouter()
        val adapter = TypeSafeJevAdapter(MockEngine { error("Unexpected TypeSafe call") })
        ServiceCredentialRouteSet(
            ServiceCredentialStore(listOf(adapter), InMemoryServiceCredentialPersistence(), InMemoryServiceSecretStore()),
        ) { 0L }.register(router)
        assertEquals(ServiceCredentialWire.METHODS, router.registeredMethods)
        assertEquals(setOf(ServiceCredentialWire.CAPABILITY), router.featureCapabilities)
    }
}
