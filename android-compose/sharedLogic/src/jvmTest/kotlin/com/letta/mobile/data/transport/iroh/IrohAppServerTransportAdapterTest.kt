package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.appserver.AppServerEndpoint
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import computer.iroh.Endpoint
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrohAppServerTransportAdapterTest {
    @Test
    fun `createTransport throws exception when scheme is not iroh`() {
        // Arrange
        val endpointMock = mockk<Endpoint>(relaxed = true)
        val adapter = IrohAppServerTransportAdapter(endpointMock)
        val invalidEndpoint = AppServerEndpoint(
            scheme = "wss",
            address = "wss://example.com"
        )
        val scope: CoroutineScope = TestScope()
        val protocol = mockk<AppServerProtocol>(relaxed = true)

        // Act & Assert
        val exception = assertFailsWith<IllegalArgumentException> {
            adapter.createTransport(invalidEndpoint, scope, protocol)
        }

        assertTrue(exception.message!!.contains("IrohAppServerTransportAdapter only handles 'iroh' scheme"))
    }
}
