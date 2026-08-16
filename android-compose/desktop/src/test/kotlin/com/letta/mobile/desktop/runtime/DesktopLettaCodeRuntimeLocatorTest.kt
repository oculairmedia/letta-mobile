package com.letta.mobile.desktop.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLettaCodeRuntimeLocatorTest {
    @Test
    fun `explicit property installation wins over packaged resources`() = withRuntimeTree { root ->
        val packaged = runtimeInstallation(File(root, "letta-code-runtime"))
        val explicit = runtimeInstallation(File(root, "explicit"))

        val located = DesktopLettaCodeRuntimeLocator.locate(
            inputs(
                properties = mapOf(
                    "letta.desktop.runtime.node" to explicit.nodeExecutable.path,
                    "letta.desktop.runtime.lettaJs" to explicit.lettaEntryPoint.path,
                ),
                resourcesRoot = root,
            ),
        )

        assertEquals(explicit, located)
        assertEquals(true, packaged.nodeExecutable.isFile)
    }

    @Test
    fun `incomplete explicit pair falls back to packaged resources`() = withRuntimeTree { root ->
        val packaged = runtimeInstallation(File(root, "windows/letta-code-runtime"))

        val located = DesktopLettaCodeRuntimeLocator.locate(
            inputs(
                properties = mapOf("letta.desktop.runtime.node" to File(root, "partial-node.exe").path),
                resourcesRoot = root,
            ),
        )

        assertEquals(packaged, located)
    }

    @Test
    fun `invalid complete explicit pair fails closed`() = withRuntimeTree { root ->
        runtimeInstallation(File(root, "letta-code-runtime"))

        val located = DesktopLettaCodeRuntimeLocator.locate(
            inputs(
                properties = mapOf(
                    "letta.desktop.runtime.node" to File(root, "missing-node.exe").path,
                    "letta.desktop.runtime.lettaJs" to File(root, "missing-letta.js").path,
                ),
                resourcesRoot = root,
            ),
        )

        assertNull(located)
    }

    @Test
    fun `missing runtime files return null`() = withRuntimeTree { root ->
        assertNull(DesktopLettaCodeRuntimeLocator.locate(inputs(resourcesRoot = root)))
    }

    @Test
    fun `packaged Windows runtime is ignored on other hosts`() = withRuntimeTree { root ->
        runtimeInstallation(File(root, "windows/letta-code-runtime"))

        assertNull(DesktopLettaCodeRuntimeLocator.locate(inputs(resourcesRoot = root, isWindows = false)))
    }

    @Test
    fun `explicit runtime remains available on other hosts`() = withRuntimeTree { root ->
        val explicit = runtimeInstallation(File(root, "explicit"))

        val located = DesktopLettaCodeRuntimeLocator.locate(
            inputs(
                properties = mapOf(
                    "letta.desktop.runtime.node" to explicit.nodeExecutable.path,
                    "letta.desktop.runtime.lettaJs" to explicit.lettaEntryPoint.path,
                ),
                isWindows = false,
            ),
        )

        assertEquals(explicit, located)
    }

    private fun inputs(
        properties: Map<String, String> = emptyMap(),
        environment: Map<String, String> = emptyMap(),
        resourcesRoot: File? = null,
        isWindows: Boolean = true,
    ) = DesktopRuntimeLocationInputs(
        property = properties::get,
        environment = environment::get,
        resourcesRoot = resourcesRoot,
        isWindows = isWindows,
    )

    private fun runtimeInstallation(root: File): DesktopLettaCodeInstallation {
        val node = File(root, "node.exe").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        val letta = File(root, "node_modules/@letta-ai/letta-code/letta.js").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        return DesktopLettaCodeInstallation(node, letta)
    }

    private fun withRuntimeTree(block: (File) -> Unit) {
        val root = createTempDirectory("desktop-runtime-locator").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
