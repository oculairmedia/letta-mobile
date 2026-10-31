package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminRpcRouterTest {

    @Test
    fun testCopyHandlersFrom_populatedRouter() {
        val sourceRouter = AdminRpcRouter()
        sourceRouter.register("test.method") { JsonPrimitive("result") }
        sourceRouter.featureCapabilities = setOf("capability1", "capability2")

        val targetRouter = AdminRpcRouter()
        targetRouter.copyHandlersFrom(sourceRouter)

        assertEquals(1, targetRouter.methodCount)
        assertTrue(targetRouter.registeredMethods.contains("test.method"))
        assertEquals(setOf("capability1", "capability2"), targetRouter.featureCapabilities)
    }

    @Test
    fun testCopyHandlersFrom_emptyRouter_doesNotThrow() {
        val sourceRouter = AdminRpcRouter()
        val targetRouter = AdminRpcRouter()

        targetRouter.copyHandlersFrom(sourceRouter)

        assertEquals(0, targetRouter.methodCount)
        assertTrue(targetRouter.featureCapabilities.isEmpty())
    }
}
