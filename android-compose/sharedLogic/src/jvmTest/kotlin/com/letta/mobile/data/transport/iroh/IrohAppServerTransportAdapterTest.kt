package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrohAppServerTransportAdapterTest {
    @Test
    fun `createTransport throws exception when scheme is not iroh`() = runTest {
        // Arrange
        val endpoint = Endpoint.bind(EndpointOptions())
        try {
            val adapter = IrohAppServerTransportAdapter(endpoint)
            val invalidEndpoint = AppServerEndpoint(
                scheme = "wss",
                address = "wss://example.com"
            )
            val scope: CoroutineScope = TestScope()

            // Act & Assert
            val exception = assertFailsWith<IllegalArgumentException> {
                adapter.createTransport(invalidEndpoint, scope, AppServerProtocol)
            }

            assertTrue(exception.message!!.contains("IrohAppServerTransportAdapter only handles 'iroh' scheme"))
        } finally {
            endpoint.close()
        }
    }
}
