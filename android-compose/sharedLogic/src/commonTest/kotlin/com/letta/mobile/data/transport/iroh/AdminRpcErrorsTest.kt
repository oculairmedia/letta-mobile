package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminRpcErrorsTest {
    @Test
    fun unknownMethodMessageMatchesRouterPrefix() {
        assertEquals("Unknown method: subagent.list", AdminRpcErrors.unknownMethod("subagent.list"))
        assertTrue(AdminRpcErrors.isUnknownMethod(AdminRpcErrors.unknownMethod("subagent.todos")))
        assertTrue(AdminRpcErrors.isUnknownMethod("unknown method: message.list"))
        assertFalse(AdminRpcErrors.isUnknownMethod("forbidden"))
        assertFalse(AdminRpcErrors.isUnknownMethod(null))
    }
}
