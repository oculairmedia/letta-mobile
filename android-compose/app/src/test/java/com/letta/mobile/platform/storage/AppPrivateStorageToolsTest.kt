package com.letta.mobile.platform.storage

import com.letta.mobile.platform.systemaccess.SystemAccessCapability
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityStatus
import com.letta.mobile.platform.systemaccess.SystemAccessToolCheck
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPrivateStorageToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `write read and list stay inside app private root`() {
        val tools = tools()

        val write = tools.write(
            root = AppPrivateStorageRoot.Files,
            path = "notes/hello.txt",
            content = "hello".encodeToByteArray(),
        ) as StorageToolResult.Success
        assertTrue(write.value.created)
        assertEquals(5, write.value.bytesWritten)

        val read = tools.read(
            root = AppPrivateStorageRoot.Files,
            path = "notes/hello.txt",
        ) as StorageToolResult.Success
        assertArrayEquals("hello".encodeToByteArray(), read.value.content)
        assertFalse(read.value.truncated)

        val list = tools.list(
            root = AppPrivateStorageRoot.Files,
            path = "notes",
        ) as StorageToolResult.Success
        assertEquals(listOf("hello.txt"), list.value.entries.map { it.name })
    }

    @Test
    fun `canonical path traversal is rejected`() {
        val tools = tools()

        val result = tools.read(
            root = AppPrivateStorageRoot.Files,
            path = "../escape.txt",
        ) as StorageToolResult.Failure

        assertEquals(StorageToolErrorCode.InvalidPath, result.error.code)
    }

    @Test
    fun `symlink escape is rejected`() {
        val roots = TestRoots(temporaryFolder.newFolder("sandbox"))
        val outside = temporaryFolder.newFolder("outside")
        val link = File(roots.files, "link")
        link.parentFile?.mkdirs()
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val tools = AppPrivateStorageTools(roots, AllowingRegistry())

        val result = tools.write(
            root = AppPrivateStorageRoot.Files,
            path = "link/escape.txt",
            content = "nope".encodeToByteArray(),
        ) as StorageToolResult.Failure

        assertEquals(StorageToolErrorCode.InvalidPath, result.error.code)
    }

    @Test
    fun `read output is truncated at limit`() {
        val tools = tools()
        tools.write(
            root = AppPrivateStorageRoot.Files,
            path = "large.txt",
            content = "abcdef".encodeToByteArray(),
        )

        val result = tools.read(
            root = AppPrivateStorageRoot.Files,
            path = "large.txt",
            maxBytes = 3,
        ) as StorageToolResult.Success

        assertEquals("abc", result.value.content.decodeToString())
        assertTrue(result.value.truncated)
    }

    @Test
    fun `directory listing is capped`() {
        val tools = tools()
        repeat(3) { index ->
            tools.write(
                root = AppPrivateStorageRoot.Files,
                path = "items/$index.txt",
                content = index.toString().encodeToByteArray(),
            )
        }

        val result = tools.list(
            root = AppPrivateStorageRoot.Files,
            path = "items",
            limit = 2,
        ) as StorageToolResult.Success

        assertEquals(2, result.value.entries.size)
        assertTrue(result.value.truncated)
    }

    @Test
    fun `tool access denies fail closed before file IO`() {
        val roots = TestRoots(temporaryFolder.newFolder("sandbox"))
        val tools = AppPrivateStorageTools(roots, DenyingRegistry())

        val result = tools.write(
            root = AppPrivateStorageRoot.Files,
            path = "denied.txt",
            content = "nope".encodeToByteArray(),
        ) as StorageToolResult.Failure

        assertEquals(StorageToolErrorCode.ToolDenied, result.error.code)
        assertFalse(File(roots.files, "denied.txt").exists())
    }

    private fun tools(): AppPrivateStorageTools = AppPrivateStorageTools(
        rootProvider = TestRoots(temporaryFolder.newFolder("sandbox")),
        capabilityRegistry = AllowingRegistry(),
    )

    private class TestRoots(base: File) : AppPrivateStorageRootProvider {
        val files: File = File(base, "files").apply { mkdirs() }
        private val cache: File = File(base, "cache").apply { mkdirs() }
        private val noBackup: File = File(base, "no_backup").apply { mkdirs() }

        override fun rootDirectory(root: AppPrivateStorageRoot): File = when (root) {
            AppPrivateStorageRoot.Files -> files
            AppPrivateStorageRoot.Cache -> cache
            AppPrivateStorageRoot.NoBackup -> noBackup
        }
    }

    private class AllowingRegistry : SystemAccessCapabilityRegistry {
        override fun listCapabilities(): List<SystemAccessCapability> = emptyList()
        override fun getCapability(id: String): SystemAccessCapability? = null
        override fun checkToolAccess(toolId: String): SystemAccessToolCheck = SystemAccessToolCheck(
            toolId = toolId,
            allowed = true,
            status = SystemAccessCapabilityStatus.Granted,
            reason = "allowed",
        )
    }

    private class DenyingRegistry : SystemAccessCapabilityRegistry {
        override fun listCapabilities(): List<SystemAccessCapability> = emptyList()
        override fun getCapability(id: String): SystemAccessCapability? = null
        override fun checkToolAccess(toolId: String): SystemAccessToolCheck = SystemAccessToolCheck(
            toolId = toolId,
            allowed = false,
            reason = "denied",
        )
    }
}
