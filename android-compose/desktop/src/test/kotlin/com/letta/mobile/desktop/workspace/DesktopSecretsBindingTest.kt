package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.secrets.AgentSecretsException
import com.letta.mobile.data.secrets.AgentSecretsFeature
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The desktop binds the secrets vault to its direct App Server session (letta-mobile-bzvro.25). */
class DesktopSecretsBindingTest {
    @Test
    fun `without a direct session the vault explains why`() = runTest {
        val sources = DesktopWorkspaceSources(DesktopLocalAppServerClientRegistry())
        val error = assertFailsWith<AgentSecretsException> { sources.secrets().list("agent-1") }
        assertEquals(DesktopWorkspaceSources.NO_DIRECT_SESSION, error.message)
    }

    @Test
    fun `the vault is on for desktop and off for android by default`() {
        assertTrue(AgentSecretsFeature.Desktop.enabled)
        assertFalse(AgentSecretsFeature.Android.enabled)
    }
}
