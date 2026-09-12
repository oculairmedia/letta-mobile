package com.letta.mobile.desktop.data

import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Flushes a directory entry so a rename published inside it survives a crash.
 *
 * POSIX lets a directory be opened read-only and fsynced, and without that a freshly published
 * generation can be durable while the directory entry naming it is not. Windows refuses to open a
 * directory as a file at all and throws [AccessDeniedException], so the same call there is not a
 * weaker guarantee, it is an unconditional failure of every ledger write.
 *
 * Swallowing only that one exception keeps the POSIX durability behaviour exactly as it was while
 * letting Windows fall back to what NTFS already gives: metadata operations reach the volume's log
 * in order, so the rename is not reordered behind the data it publishes. Any other IO failure still
 * propagates, because a real write error must not be mistaken for the platform saying no.
 */
internal fun syncDirectoryEntry(directory: Path) {
    try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (rejected: AccessDeniedException) {
        // Windows: directories are not openable as files. Nothing to flush here.
    }
}
