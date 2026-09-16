package com.letta.mobile.data.transport.iroh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IrohDialerTest {

    @Test
    fun dialRequiresValidIrohUrl() = runTest(UnconfinedTestDispatcher()) {
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = {},
        )

        val nonIrohConfig = IrohConnectConfig(
            baseShimUrl = "http://localhost:8080",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        val err = assertFailsWith<IllegalStateException> {
            dialer.dial(nonIrohConfig)
        }
        assertTrue(err.message.orEmpty().contains("requires backend URL iroh://"))
    }

    @Test
    fun dialInvokesConnectingCallbackBeforeBinding() = runTest(UnconfinedTestDispatcher()) {
        var connectingCalled = false
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = {},
            bindEndpoint = { throw RuntimeException("mock bind failed") },
        )

        val config = IrohConnectConfig(
            baseShimUrl = "iroh://invalid-ticket",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        runCatching {
            dialer.dial(config, onConnecting = { connectingCalled = true })
        }

        assertTrue(connectingCalled, "onConnecting callback must be invoked when dial begins")
    }

    @Test
    fun closeResourcesInvokedOnDialFailure() = runTest(UnconfinedTestDispatcher()) {
        var closeReason: String? = null
        val dialer = IrohDialer(
            scope = this,
            secretKeyStore = FakeSecretKeyStore(),
            onConnectionLost = { _, _ -> },
            onCloseResources = { reason -> closeReason = reason },
            bindEndpoint = { throw RuntimeException("mock bind failed") },
        )

        val config = IrohConnectConfig(
            baseShimUrl = "iroh://invalid-ticket-that-fails",
            token = "token",
            deviceId = "dev",
            clientVersion = "1.0",
        )

        runCatching {
            dialer.dial(config)
        }

        assertEquals("dial_failed", closeReason)
    }

    private class FakeSecretKeyStore(private val key: ByteArray = ByteArray(32)) : com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStore {
        override suspend fun loadOrCreate(): ByteArray = key
    }
}
