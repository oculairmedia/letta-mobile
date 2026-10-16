package com.letta.mobile.data.backend

import com.letta.mobile.data.chat.recompile.IrohConversationRecompileRpc
import com.letta.mobile.data.model.BackendKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.13: the (platform x backend kind x route) contract. Each platform's own matrix
 * test (Android `AndroidSessionRouteMatrixTest`, desktop `DesktopSessionAdapterRouteMatrixTest`)
 * holds its factory to its row; this one holds the rows to each other.
 */
class SessionRouteMatrixTest {
    private val kinds = listOf<BackendKind?>(null) + BackendKind.entries

    @Test
    fun everyCellWherePlatformsDisagreeIsDocumentedAndNoOtherIs() {
        assertEquals(
            SessionRouteMatrix.DIVERGENCES.keys,
            SessionRouteMatrix.disagreements(),
            "a platform row changed: document the new divergence with its reason, or drop the stale one",
        )
        assertTrue(SessionRouteMatrix.DIVERGENCES.values.all { it.isNotBlank() })
    }

    @Test
    fun theBrowserServesDesktopsRowForEveryHostItCanReach() {
        // letta-mobile-ckmub.17.1: both build the one shared session graph.
        for (kind in listOf(BackendKind.DIRECT_WS, BackendKind.IROH)) {
            assertEquals(
                SessionRouteMatrix.bound(SessionPlatform.DESKTOP, kind),
                SessionRouteMatrix.bound(SessionPlatform.WEB, kind),
                "$kind",
            )
        }
    }

    @Test
    fun noBackendConfiguredServesNoRouteOnAnyPlatform() {
        for (platform in SessionPlatform.entries) {
            assertTrue(SessionRouteMatrix.bound(platform, null).isEmpty(), "$platform")
        }
    }

    @Test
    fun routesNoBackendCarriesAreServedNowhere() {
        val nowhere = setOf(SessionRoute.PROJECT_WORK, SessionRoute.STEPS, SessionRoute.VIBESYNC)
        for (platform in SessionPlatform.entries) {
            for (kind in kinds) {
                assertTrue((SessionRouteMatrix.bound(platform, kind) intersect nowhere).isEmpty(), "$platform x $kind")
            }
        }
    }

    @Test
    fun everyRouteIsServedOrDeliberatelyUnservedForEveryCell() {
        // The rows are exhaustive `when`s, so this is the runtime half: every cell has an answer.
        val cells = SessionPlatform.entries.flatMap { platform ->
            kinds.flatMap { kind -> SessionRoute.entries.map { route -> Triple(platform, kind, route in SessionRouteMatrix.bound(platform, kind)) } }
        }
        assertEquals(SessionPlatform.entries.size * kinds.size * SessionRoute.entries.size, cells.size)
    }
}

class SessionCapabilitiesTest {
    @Test
    fun aHostGatedRouteNeedsTheHostCapabilityOnTopOfTheRow() {
        var advertised = emptySet<String>()
        val capabilities = SessionCapabilities.forSession(SessionPlatform.ANDROID, BackendKind.IROH, { advertised })
        assertFalse(capabilities.supports(SessionRoute.CONVERSATION_RECOMPILE))
        advertised = setOf(IrohConversationRecompileRpc.CAPABILITY)
        assertTrue(capabilities.supports(SessionRoute.CONVERSATION_RECOMPILE), "the host features are read live")
        assertEquals(IrohConversationRecompileRpc.CAPABILITY, SessionRoute.CONVERSATION_RECOMPILE.hostCapability)
    }

    @Test
    fun aRouteOutsideTheRowIsNotSupportedWhateverTheHostAdvertises() {
        val capabilities = SessionCapabilities.forSession(SessionPlatform.DESKTOP, BackendKind.IROH, { setOf(IrohConversationRecompileRpc.CAPABILITY) })
        assertFalse(capabilities.supports(SessionRoute.CONVERSATION_RECOMPILE))
        assertFalse(capabilities.supports(SessionRoute.STEPS))
        assertTrue(capabilities.supports(SessionRoute.RUNS))
    }

    @Test
    fun boundRemoteSourcesExtendTheRowAndNoneServesNothing() {
        val capabilities = SessionCapabilities.forSession(SessionPlatform.ANDROID, BackendKind.DIRECT_WS, extraBound = setOf(SessionRoute.STEPS))
        assertEquals(setOf(SessionRoute.STEPS), capabilities.bound)
        assertTrue(SessionRoute.entries.none(SessionCapabilities.None::supports))
    }
}

class RemoteRouteTest {
    interface Source

    object TheSource : Source

    @Test
    fun anAbsentRouteIsAValueAndItsSourceIsTheRouteBackstop() {
        val absent: Source? = null
        val route: RemoteRoute<Source> = absent.routeOr(SessionRoute.RUNS)
        assertFalse(route.isBound)
        assertNull(route.orNull())
        val error = assertFailsWith<AppServerRouteUnavailable> { route.source("run.list") }
        assertEquals(SessionRoute.RUNS, error.route)
        assertTrue(error.message.orEmpty().contains("run.list"))
    }

    @Test
    fun aBoundRouteHandsBackItsSource() {
        val route: RemoteRoute<Source> = TheSource.bound()
        assertTrue(route.isBound)
        assertSame(TheSource, route.orNull())
        assertSame(TheSource, route.source())
        assertSame(TheSource, TheSource.routeOr(SessionRoute.RUNS).source())
    }
}
