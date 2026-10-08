package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.storage.SecureSettingsStore
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** letta-mobile-bzvro.5 (F05): heap, stable instance id and working directory of the child. */
class DesktopRuntimeLaunchSettingsTest {
    private val root = createTempDirectory("desktop-runtime-launch").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        DesktopRuntimeLaunchPreference.instanceId = null
        DesktopRuntimeLaunchPreference.workingDirectory = null
    }

    private fun environmentFor(gigabytes: Long?, inherited: String? = null): Map<String, String> =
        desktopRuntimeLaunchContext(
            DesktopRuntimeLaunchInputs(
                totalMemoryBytes = gigabytes?.let { it * 1024L * 1024L * 1024L },
                instanceId = "install-1",
                workingDirectory = root,
                inheritedNodeOptions = inherited,
            ),
        ).environment

    @Test
    fun `heap is half of memory with a four gigabyte floor`() {
        assertEquals("--max-old-space-size=4096", environmentFor(8)["NODE_OPTIONS"])
        assertEquals("--max-old-space-size=8192", environmentFor(16)["NODE_OPTIONS"])
        assertEquals("--max-old-space-size=32768", environmentFor(64)["NODE_OPTIONS"])
        assertEquals("--max-old-space-size=4096", environmentFor(4)["NODE_OPTIONS"], "small machines keep the floor")
        assertEquals("--max-old-space-size=4096", environmentFor(null)["NODE_OPTIONS"], "unknown memory keeps the floor")
    }

    @Test
    fun `inherited node options are kept and an explicit heap wins`() {
        assertEquals("--enable-source-maps --max-old-space-size=8192", environmentFor(16, "--enable-source-maps")["NODE_OPTIONS"])
        assertEquals("--max-old-space-size=1024", environmentFor(16, "--max-old-space-size=1024")["NODE_OPTIONS"])
    }

    @Test
    fun `environment carries the instance id and the working directory`() {
        val env = environmentFor(16)

        assertEquals("desktop-local:install-1", env["LETTA_LISTENER_INSTANCE_ID"])
        assertEquals(root.absolutePath, env["USER_CWD"])
        assertEquals("1", env["PERSIST_CWD"])
    }

    @Test
    fun `working directory falls back from preference to documents to home to temp`() {
        val home = File(root, "home").apply { mkdirs() }
        val documents = File(home, "Documents")
        val temp = File(root, "tmp").apply { mkdirs() }
        val preferred = File(root, "projects").apply { mkdirs() }

        assertEquals(preferred, resolveDesktopRuntimeWorkingDirectory(preferred, home, temp))
        assertEquals(home, resolveDesktopRuntimeWorkingDirectory(File(root, "gone"), home, temp), "no Documents yet")
        documents.mkdirs()
        assertEquals(documents, resolveDesktopRuntimeWorkingDirectory(null, home, temp))
        assertEquals(temp, resolveDesktopRuntimeWorkingDirectory(null, null, temp))
    }

    @Test
    fun `instance id is minted once and stable across restarts`() {
        val store = InMemoryStore()
        var minted = 0
        val first = DesktopRuntimeLaunchSettings.loadOrCreateInstanceId(store) { "id-${++minted}" }
        val second = DesktopRuntimeLaunchSettings.loadOrCreateInstanceId(store) { "id-${++minted}" }

        assertEquals("id-1", first)
        assertEquals(first, second)
        assertEquals(1, minted)
    }

    @Test
    fun `saving a working directory validates it and applies it live`() {
        val store = InMemoryStore()
        val dir = File(root, "work").apply { mkdirs() }

        assertNotNull(DesktopRuntimeLaunchSettings.saveWorkingDirectory(store, File(root, "missing").path))
        assertNull(DesktopRuntimeLaunchSettings.readWorkingDirectory(store))

        assertNull(DesktopRuntimeLaunchSettings.saveWorkingDirectory(store, dir.path))
        assertEquals(dir.absolutePath, DesktopRuntimeLaunchSettings.readWorkingDirectory(store))
        assertEquals(dir, DesktopRuntimeLaunchPreference.workingDirectory)

        DesktopRuntimeLaunchSettings.resetWorkingDirectory(store)
        assertNull(DesktopRuntimeLaunchPreference.workingDirectory)
    }

    @Test
    fun `the child is spawned in the preferred directory with the launch environment`() {
        val work = File(root, "work").apply { mkdirs() }
        val specs = mutableListOf<DesktopRuntimeLaunchSpec>()
        val manager = DesktopLocalRuntimeManager(
            installationProvider = {
                DesktopLettaCodeInstallation(
                    File(root, "node.exe").apply { createNewFile() },
                    File(root, "letta.js").apply { createNewFile() },
                )
            },
            backendDirectory = { File(root, "backend") },
            processLauncher = DesktopRuntimeProcessLauncher { spec ->
                specs += spec
                ReadyProcess()
            },
            logLine = {},
            readyTimeoutMs = 2_000,
            stopTimeoutMs = 1,
            launchContext = {
                desktopRuntimeLaunchContext(DesktopRuntimeLaunchInputs(16L shl 30, "install-1", work))
            },
        )
        manager.ensureStarted()
        manager.close()

        val spec = specs.single()
        assertEquals(work, spec.workingDirectory)
        assertEquals("--max-old-space-size=8192", spec.environment["NODE_OPTIONS"])
        assertEquals("1", spec.environment["LETTA_LOCAL_BACKEND_EXPERIMENTAL"], "the backend env is still set")
    }

    private class ReadyProcess : DesktopRuntimeProcess {
        override val stdout = BufferedReader(StringReader("Listening on ws://127.0.0.1:4500\n"))
        override val stderr = BufferedReader(StringReader(""))
        override val descendants: List<DesktopRuntimeProcessHandle> = emptyList()
        private var alive = true
        override val isAlive: Boolean get() = alive
        override val exitCodeOrNull: Int? get() = null
        override fun waitFor(timeoutMs: Long): Boolean = !alive
        override fun onExit(callback: (exitCode: Int?) -> Unit) = Unit
        override fun destroy() { alive = false }
        override fun destroyForcibly() { alive = false }
    }

    private class InMemoryStore : SecureSettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, defaultValue: String?): String? = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }
}
