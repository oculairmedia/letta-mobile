package com.letta.mobile.data.transport.appserver

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-by4vm.82: [DualLaneAppServerClient] must route every [AppServerClient] request to a
 * lane. A request it does not override falls to the interface default, which throws
 * UnsupportedOperationException: `execute_command` did, so the Iroh host answered every
 * `/context-limit` with capability_unavailable and the drawer's limit slider vanished.
 */
class DualLaneAppServerClientCoverageTest {
    @Test
    fun everyClientRequestIsRoutedToALane() {
        val dual = DualLaneAppServerClient::class.java
        val unrouted = AppServerClient::class.java.methods
            .filter { it.isDefault && !Modifier.isStatic(it.modifiers) }
            .filter { dual.getMethod(it.name, *it.parameterTypes).declaringClass != dual }
            .map { it.name }
            .sorted()

        assertEquals(emptyList(), unrouted)
    }
}
