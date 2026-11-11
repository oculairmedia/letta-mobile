package com.letta.mobile.data.hoststate

import java.nio.channels.FileChannel
import java.nio.file.Path

/** Host directory barriers used by file sidecars and host startup validation. */
object HostDirectoryDurability {
    /** Whether this JVM host supports opening directory channels for durability confirmation. */
    fun supported(osName: String = System.getProperty("os.name", "")): Boolean =
        !osName.startsWith("Windows", ignoreCase = true)

    /** Refuse security-record administration before any filesystem or locking side effects. */
    fun requireNotebookSharing(supported: Boolean = supported()) {
        if (!supported) throw UnsupportedOperationException(
            "Notebook sharing needs a host that can confirm directory durability; not available on a Windows host",
        )
    }

    internal fun sync(directory: Path) {
        FileChannel.open(directory).use { it.force(true) }
    }

    internal fun syncMount(directory: Path) {
        if (supported()) sync(directory)
    }
}
