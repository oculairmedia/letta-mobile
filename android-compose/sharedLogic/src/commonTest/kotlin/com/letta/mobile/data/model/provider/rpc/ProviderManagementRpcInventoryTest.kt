package com.letta.mobile.data.model.provider.rpc

import com.letta.mobile.data.model.provider.wire.PROVIDER_MANAGEMENT_CONTRACT_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderManagementRpcInventoryTest {

    @Test
    fun rpcAndWireContractVersionsCannotDrift() {
        assertEquals(PROVIDER_MANAGEMENT_CONTRACT_VERSION, ProviderRpcMethods.CONTRACT_VERSION)
        assertEquals("provider_management_v1", ProviderRpcMethods.CONTRACT_NAME)
    }

    @Test
    fun methodInventoryContainsExactExpectedMethods() {
        val expectedReadMethods = setOf(
            "provider.definition.list",
            "provider.instance.list",
            "provider.instance.get",
            "model.route.list",
        )

        val expectedWriteMethods = setOf(
            "provider.instance.create",
            "provider.instance.update",
            "provider.instance.set_enabled",
            "provider.instance.delete",
            "provider.instance.validate",
            "provider.credential.replace",
            "provider.credential.clear",
            "model.visibility.set",
            "model.visibility.reset",
        )

        assertEquals(expectedReadMethods, ProviderRpcMethods.READ_METHODS)
        assertEquals(expectedWriteMethods, ProviderRpcMethods.WRITE_METHODS)
        assertEquals(expectedReadMethods + expectedWriteMethods, ProviderRpcMethods.ALL_METHODS)
    }

    @Test
    fun readAndWriteClassificationsAreDisjointAndAccurate() {
        for (m in ProviderRpcMethods.READ_METHODS) {
            assertTrue(ProviderRpcMethods.isReadMethod(m))
            assertFalse(ProviderRpcMethods.isWriteMethod(m))
        }

        for (m in ProviderRpcMethods.WRITE_METHODS) {
            assertTrue(ProviderRpcMethods.isWriteMethod(m))
            assertFalse(ProviderRpcMethods.isReadMethod(m))
        }
    }

    @Test
    fun capabilityWireDecodingMapsKnownAndUnknownStrings() {
        assertEquals(ProviderManagementCapability.Read, ProviderManagementCapability.fromWire("provider_management:read"))
        assertEquals(ProviderManagementCapability.Read, ProviderManagementCapability.fromWire("read"))
        assertEquals(ProviderManagementCapability.Write, ProviderManagementCapability.fromWire("provider_management:write"))
        assertEquals(ProviderManagementCapability.Write, ProviderManagementCapability.fromWire("write"))

        val unknown = ProviderManagementCapability.fromWire("provider_management:admin_super")
        assertIs<ProviderManagementCapability.Unknown>(unknown)
        assertEquals("provider_management:admin_super", unknown.raw)
    }
}
