package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrohAppServerTransportAdapterTest {
    @Test
    fun `createTransport throws exception when scheme is not iroh`() {
        // Arrange
        val endpoint = Endpoint.bind(EndpointOptions())
        try {
            val adapter = IrohAppServerTransportAdapter(endpoint)
            val invalidEndpoint = AppServerEndpoint(
                scheme = "wss",
                address = "wss://example.com"
            )
            val scope: CoroutineScope = TestScope()
            // Using a dummy stub since we can't use mockk for this test properly right now
            val protocol = object : AppServerProtocol {
                override val id: String = "dummy"
            }

            // Act & Assert
            val exception = assertFailsWith<IllegalArgumentException> {
                adapter.createTransport(invalidEndpoint, scope, protocol)
            }

            assertTrue(exception.message!!.contains("IrohAppServerTransportAdapter only handles 'iroh' scheme"))
        } finally {
            endpoint.close()
        }
    }
}
