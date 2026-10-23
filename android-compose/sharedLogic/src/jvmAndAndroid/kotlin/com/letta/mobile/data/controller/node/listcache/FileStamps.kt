package com.letta.mobile.data.controller.node.listcache

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * One file's contribution to a signature: path, modified time at full precision and size, mixed so
 * that changing any one of them changes about half the bits. Signatures sum these, so the order
 * files are listed in does not matter.
 */
internal fun stampMix(path: String, modifiedNanos: Long, sizeBytes: Long): Long {
    var h = path.hashCode().toLong()
    h = h * MIX_PRIME + modifiedNanos
    h = h * MIX_PRIME + sizeBytes
    h = (h xor (h ushr 33)) * MIX_MULTIPLIER
    return h xor (h ushr 29)
}

/**
 * A value that changes whenever a `<parent>/<dir>/<recordName>` file is added, removed or rewritten,
 * from one listing and one attribute read per directory. Null when [parent] cannot be listed.
 */
internal fun recordFilesSignature(parent: File, recordName: String): Long? {
    val dirs = parent.listFiles { f -> f.isDirectory } ?: return null
    return dirs.fold(dirs.size.toLong()) { sum, dir -> sum + (File(dir, recordName).stampMixOrNull() ?: 0L) }
}

private fun File.stampMixOrNull(): Long? = runCatching {
    val attributes = Files.readAttributes(toPath(), BasicFileAttributes::class.java)
    stampMix(path, attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.size())
}.getOrNull()

private const val MIX_PRIME = 1_000_003L

/** SplitMix64's 0xbf58476d1ce4e5b9, so one changed field flips about half the bits. */
private const val MIX_MULTIPLIER = -0x40a7b892e31b1a47L
