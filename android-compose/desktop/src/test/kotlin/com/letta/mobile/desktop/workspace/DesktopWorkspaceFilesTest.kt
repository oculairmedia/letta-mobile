package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.workspace.WorkspaceFileException
import com.letta.mobile.desktop.runtime.DesktopLocalAppServerClientRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/** The desktop binds workspace files to its direct App Server session (letta-mobile-bzvro.26). */
class DesktopWorkspaceFilesTest {
    @Test
    fun `with neither a direct session nor an iroh host file search explains why`() = runTest {
        val sources = DesktopWorkspaceSources(DesktopLocalAppServerClientRegistry(), irohTransport = { null })
        val error = assertFailsWith<WorkspaceFileException> { sources.files().search("main", "/repo", 25) }
        assertEquals(DesktopWorkspaceSources.NO_DIRECT_SESSION, error.message)
    }
}
