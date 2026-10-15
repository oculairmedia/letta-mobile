package com.letta.mobile.data.backend

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.13: the one stand-in for an unserved contract throws on every call — a property
 * read and a list or flow return included. There is no quietly-empty variant any more.
 */
class UnavailableRouteTest {
    interface Catalog {
        val items: StateFlow<List<String>>
        val enabled: Boolean
        fun names(): List<String>
        suspend fun count(): Int
    }

    @Test
    fun everyMemberOfAnUnavailableContractThrowsItsRoute() = runTest {
        val catalog = unavailable<Catalog>(SessionRoute.TOOLS)
        val reads: List<suspend () -> Unit> = listOf(
            { catalog.items },
            { catalog.enabled },
            { catalog.names() },
            { catalog.count() },
        )
        for (read in reads) {
            val error = assertFailsWith<AppServerRouteUnavailable> { read() }
            assertEquals(SessionRoute.TOOLS, error.route)
            assertTrue(error.message.orEmpty().contains("Catalog."), error.message)
        }
    }

    @Test
    fun theStandInIsRecognisableAndItsObjectMethodsDoNotThrow() {
        val catalog = unavailable<Catalog>(SessionRoute.MODELS)
        assertTrue(isUnavailable(catalog))
        assertEquals(SessionRoute.MODELS, unavailableRouteOf(catalog))
        assertEquals("Unavailable(Catalog, MODELS)", catalog.toString())
        assertEquals(catalog, catalog)
        assertEquals(catalog.hashCode(), catalog.hashCode())
        assertFalse(isUnavailable("not a proxy"))
        assertNull(unavailableRouteOf(null))
    }

    @Test
    fun onlyAnInterfaceCanBeStoodIn() {
        assertFailsWith<IllegalArgumentException> { unavailable(String::class.java, SessionRoute.AGENTS) }
    }
}
