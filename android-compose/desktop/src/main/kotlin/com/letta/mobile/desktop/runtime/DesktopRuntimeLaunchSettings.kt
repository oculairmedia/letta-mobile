package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.storage.SecureSettingsStore
import java.io.File
import java.lang.management.ManagementFactory
import java.util.UUID

/**
 * Launch parity with the reference desktop app for the bundled letta-code child
 * (letta-mobile-bzvro.5, F05):
 *
 * - `NODE_OPTIONS=--max-old-space-size` at half of physical RAM with a 4 GB floor, so large
 *   repositories and long contexts do not run the Node default heap out of memory.
 * - A stable per-install `LETTA_LISTENER_INSTANCE_ID`, so a manually started `letta server`
 *   cannot be mistaken for our child.
 * - `USER_CWD` (plus `PERSIST_CWD=1`) and the process working directory from a "default working
 *   directory" preference, falling back to Documents, then home, then the temp directory,
 *   instead of the app's install directory.
 */
internal data class DesktopRuntimeLaunchInputs(
    val totalMemoryBytes: Long?,
    val instanceId: String,
    val workingDirectory: File,
    /** The NODE_OPTIONS the desktop process itself inherited, kept and extended. */
    val inheritedNodeOptions: String? = null,
)

internal const val DESKTOP_RUNTIME_HEAP_FLOOR_MB = 4_096

/** Half of [totalMemoryBytes] in MB, never below [DESKTOP_RUNTIME_HEAP_FLOOR_MB]; the floor when unknown. */
internal fun desktopRuntimeHeapMb(totalMemoryBytes: Long?): Int {
    val halfMb = totalMemoryBytes?.takeIf { it > 0 }?.let { it / 2 / (1024L * 1024L) } ?: 0L
    return maxOf(halfMb, DESKTOP_RUNTIME_HEAP_FLOOR_MB.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun desktopRuntimeLaunchContext(inputs: DesktopRuntimeLaunchInputs): DesktopRuntimeLaunchContext {
    val inherited = inputs.inheritedNodeOptions?.trim().orEmpty()
    val nodeOptions = if ("--max-old-space-size" in inherited) {
        inherited
    } else {
        listOf(inherited, "--max-old-space-size=${desktopRuntimeHeapMb(inputs.totalMemoryBytes)}")
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
    val cwd = inputs.workingDirectory.absolutePath
    return DesktopRuntimeLaunchContext(
        environment = mapOf(
            "NODE_OPTIONS" to nodeOptions,
            "LETTA_LISTENER_INSTANCE_ID" to "$INSTANCE_ID_PREFIX${inputs.instanceId}",
            "USER_CWD" to cwd,
            "PERSIST_CWD" to "1",
        ),
        workingDirectory = inputs.workingDirectory,
    )
}

/**
 * The first existing directory of: the user's preference, Documents, home, temp. A preference
 * that no longer exists falls through rather than failing the spawn.
 */
internal fun resolveDesktopRuntimeWorkingDirectory(
    preferred: File?,
    home: File?,
    temp: File,
): File = sequenceOf(preferred, home?.let { File(it, "Documents") }, home)
    .filterNotNull()
    .firstOrNull { it.isDirectory }
    ?: temp

private const val INSTANCE_ID_PREFIX = "desktop-local:"

/** Live values read on every spawn; set at bootstrap and when the user saves (F05). */
internal object DesktopRuntimeLaunchPreference {
    @Volatile
    var instanceId: String? = null

    @Volatile
    var workingDirectory: File? = null

    /** The context for the next spawn, from the live preference and this machine. */
    fun currentContext(): DesktopRuntimeLaunchContext {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let(::File)
        return desktopRuntimeLaunchContext(
            DesktopRuntimeLaunchInputs(
                totalMemoryBytes = physicalMemoryBytes(),
                instanceId = instanceId ?: EPHEMERAL_INSTANCE_ID,
                workingDirectory = resolveDesktopRuntimeWorkingDirectory(
                    preferred = workingDirectory,
                    home = home,
                    temp = File(System.getProperty("java.io.tmpdir")),
                ),
                inheritedNodeOptions = System.getenv("NODE_OPTIONS"),
            ),
        )
    }

    private val EPHEMERAL_INSTANCE_ID: String by lazy { UUID.randomUUID().toString() }

    private fun physicalMemoryBytes(): Long? = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
    }.getOrNull()
}

/** Persistence for the runtime launch settings, in the desktop's [SecureSettingsStore]. */
internal object DesktopRuntimeLaunchSettings {
    private const val INSTANCE_ID_KEY = "letta.desktop.runtime.instanceId"
    private const val WORKING_DIRECTORY_KEY = "letta.desktop.runtime.defaultWorkingDirectory"

    /** The stable per-install id, minted and saved on first use. */
    fun loadOrCreateInstanceId(store: SecureSettingsStore, mint: () -> String = { UUID.randomUUID().toString() }): String =
        store.getString(INSTANCE_ID_KEY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: mint().also { store.putString(INSTANCE_ID_KEY, it) }

    fun readWorkingDirectory(store: SecureSettingsStore): String? =
        store.getString(WORKING_DIRECTORY_KEY)?.trim()?.takeIf { it.isNotEmpty() }

    /** Call once at bootstrap, before the first spawn. */
    fun applyStored(store: SecureSettingsStore) {
        DesktopRuntimeLaunchPreference.instanceId = loadOrCreateInstanceId(store)
        DesktopRuntimeLaunchPreference.workingDirectory = readWorkingDirectory(store)?.let(::File)
    }

    /** Saves [path] when it is an existing directory; returns the reason when it is not. */
    fun saveWorkingDirectory(store: SecureSettingsStore, path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "Choose a folder."
        val dir = File(trimmed)
        if (!dir.isDirectory) return "That folder does not exist."
        store.putString(WORKING_DIRECTORY_KEY, dir.absolutePath)
        DesktopRuntimeLaunchPreference.workingDirectory = dir
        return null
    }

    fun resetWorkingDirectory(store: SecureSettingsStore) {
        store.remove(WORKING_DIRECTORY_KEY)
        DesktopRuntimeLaunchPreference.workingDirectory = null
    }
}
