package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.NoOpPairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PairInviteController], the plain (non-Hilt) state machine
 * backing the server-mode invite screen.
 *
 * `mint()` hops onto real `Dispatchers.IO`/`Dispatchers.Default` internally
 * (same as `desktop/.../qr/DesktopPairInviteController.kt`, which this
 * mirrors), so `advanceUntilIdle()` alone is not sufficient — it only
 * drains the test scheduler's own queue and returns before the real
 * background dispatcher has necessarily posted its continuation back. This
 * uses the same fix the desktop test module already applies
 * (`DesktopPairInstallScreen.mintForTest()`): a suspend helper that calls
 * `mint()` then polls with `delay()` against a wall-clock deadline until
 * `loading` flips back to `false`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairInviteControllerTest {

    private val testSigner = PairQrSigner { _, _, _ -> "test-signature" }
    private val nodeIdHex = "ab".repeat(32)

    private fun controller(
        scope: CoroutineScope,
        signer: PairQrSigner = testSigner,
    ): PairInviteController = PairInviteController.forTest(
        scope = scope,
        signer = signer,
        nodeIdHex = nodeIdHex,
        pairingStore = InMemoryPairedPeerStore(),
    )

    /** Calls [PairInviteController.mint] and waits (real wall-clock, polled via [delay]) for it to settle. */
    private suspend fun PairInviteController.mintAndAwait(timeoutMs: Long = 5_000L) {
        mint()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (uiState.value.loading && System.currentTimeMillis() < deadline) {
            delay(50L)
        }
    }

    @Test
    fun `initial state is idle before mint`() = runTest {
        val c = controller(this)
        val state = c.uiState.value
        assertFalse(state.loading)
        assertNull(state.wireValue)
        assertNull(state.matrix)
        assertNull(state.error)
    }

    @Test
    fun `mint success populates wireValue matrix suggestedName and expiresAtMs`() = runTest {
        val c = controller(this)
        c.mintAndAwait()
        val state = c.uiState.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertNotNull(state.wireValue)
        assertTrue(state.wireValue!!.startsWith("letta-qr-v1."))
        assertNotNull(state.matrix)
        assertTrue(state.matrix!!.size > 0)
        assertEquals("paired-peer", state.suggestedName)
        assertNotNull(state.expiresAtMs)
    }

    @Test
    fun `mint with unconfigured signer surfaces empty-signature error`() = runTest {
        // NoOpPairQrSigner collapses qr-enablement in PairingAdminHandlers,
        // so qr_invite is omitted entirely from the response — the
        // controller must report that distinctly, not silently succeed.
        val c = controller(this, signer = NoOpPairQrSigner)
        c.mintAndAwait()
        val state = c.uiState.value
        assertFalse(state.loading)
        assertNotNull(state.error)
        assertNull(state.wireValue)
        assertTrue(state.error!!.contains("qr_invite"))
    }

    @Test
    fun `clearInvite drops wireValue matrix and expiresAtMs`() = runTest {
        val c = controller(this)
        c.mintAndAwait()
        assertNotNull(c.uiState.value.wireValue)

        c.clearInvite()
        val cleared = c.uiState.value
        assertNull(cleared.wireValue)
        assertNull(cleared.matrix)
        assertNull(cleared.expiresAtMs)

        c.mintAndAwait()
        assertNotNull(c.uiState.value.wireValue)
    }

    @Test
    fun `successive mints produce different invite secrets`() = runTest {
        val c = controller(this)
        c.mintAndAwait()
        val first = c.uiState.value.wireValue
        c.mintAndAwait()
        val second = c.uiState.value.wireValue
        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first == second)
    }
}
