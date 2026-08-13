package com.letta.mobile.ui.screens.pairing

import com.letta.mobile.data.controller.node.iroh.InMemoryPairedPeerStore
import com.letta.mobile.data.controller.node.iroh.NoOpPairQrSigner
import com.letta.mobile.data.controller.node.iroh.PairQrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [PairInviteController], the plain (non-Hilt) state machine
 * backing the server-mode invite screen. Mirrors
 * `desktop/src/test/kotlin/.../qr/DesktopPairInviteControllerTest.kt`'s
 * `runTest { ...; advanceUntilIdle() }` shape: `mint()` hops onto real
 * `Dispatchers.IO`/`Dispatchers.Default` internally (same as the desktop
 * controller), so the *default* (`StandardTestDispatcher`-backed) `runTest`
 * is used rather than `UnconfinedTestDispatcher` — the work needs to be
 * drained back onto the test scheduler explicitly.
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
        c.mint()
        advanceUntilIdle()
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
        c.mint()
        advanceUntilIdle()
        val state = c.uiState.value
        assertFalse(state.loading)
        assertNotNull(state.error)
        assertNull(state.wireValue)
        assertTrue(state.error!!.contains("qr_invite"))
    }

    @Test
    fun `clearInvite drops wireValue matrix and expiresAtMs`() = runTest {
        val c = controller(this)
        c.mint()
        advanceUntilIdle()
        assertNotNull(c.uiState.value.wireValue)

        c.clearInvite()
        val cleared = c.uiState.value
        assertNull(cleared.wireValue)
        assertNull(cleared.matrix)
        assertNull(cleared.expiresAtMs)

        c.mint()
        advanceUntilIdle()
        assertNotNull(c.uiState.value.wireValue)
    }

    @Test
    fun `successive mints produce different invite secrets`() = runTest {
        val c = controller(this)
        c.mint()
        advanceUntilIdle()
        val first = c.uiState.value.wireValue
        c.mint()
        advanceUntilIdle()
        val second = c.uiState.value.wireValue
        assertNotNull(first)
        assertNotNull(second)
        assertFalse(first == second)
    }
}
