package com.letta.mobile.desktop.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** File-storage primitive only: opaque ordered keys and values, without timeline merge semantics.
 * Pages are immutable and content addressed. Deletion is deliberately deferred: even abandoned
 * transactions retain pages, so callbacks pinned to any prior root remain safe without leases.
 */
internal class DesktopPersistentTimelineIndex(private val directory: Path) {
    data class Roots(val order: String? = null, val identity: String? = null, val evidence: String? = null,
        val tools: String? = null, val unresolved: String? = null, val toolGeneration: Long = 0)
    data class Head(val roots: Roots, val checkpoint: ByteArray, val previous: String?)
    data class Entry(val key: ByteArray, val value: ByteArray)
    data class Limits(val visitedPages: Int = 100_000, val writtenBytes: Long = 64L * 1024 * 1024) {
        init { require(visitedPages > 0 && writtenBytes >= 0) }
    }

    /** A new budget belongs to each bounded operation/batch, never to the lifetime of a reader. */
    class Budget(private val limits: Limits = Limits()) {
        var visitedPages = 0; private set
        var writtenBytes = 0L; private set
        internal fun visit() { check(visitedPages < limits.visitedPages) { "Index visit budget exceeded" }; visitedPages++ }
        internal fun write(bytes: Int) {
            check(bytes.toLong() <= limits.writtenBytes - writtenBytes) { "Index mutation byte budget exceeded" }
            writtenBytes += bytes
        }
    }

    data class Work(var encodedBytes: Long = 0, var durableBytes: Long = 0, var fileSyncs: Long = 0,
        var discardedPages: Long = 0)
    val work = Work()
    private var staged: LinkedHashMap<String, ByteArray>? = null
    private val stagedPages = mutableSetOf<String>()
    private var stagedBytes = 0L

    /** Only a bounded transaction may defer IO; standalone index operations remain durable. */
    fun beginBatch() {
        check(staged == null)
        stagedBytes = 0
        staged = linkedMapOf()
    }

    fun abandonBatch() { staged = null; stagedPages.clear() }

    private fun flushBatch(roots: Roots) {
        val pending = staged ?: return
        val reachable = mutableSetOf<String>()
        fun visit(ref: String?) {
            if (ref == null || !reachable.add(ref)) return
            val bytes = pending[ref] ?: return
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.int == MAGIC && buffer.int == VERSION)
            buffer.int; buffer.long
            visit(readRef(buffer)); visit(readRef(buffer))
        }
        listOf(roots.order, roots.identity, roots.evidence, roots.tools, roots.unresolved).forEach(::visit)
        staged = null
        for ((ref, bytes) in pending) {
            if (ref in stagedPages && ref !in reachable) { work.discardedPages++; continue }
            persist(ref, bytes)
        }
        stagedPages.clear()
    }

    private data class Node(
        val entry: Entry, val left: String?, val right: String?, val height: Int, val count: Long,
    )

    fun get(root: String?, key: ByteArray, budget: Budget): ByteArray? {
        var ref = root
        repeat(MAX_HEIGHT) {
            if (ref == null) return null
            val node = read(requireNotNull(ref), budget)
            val comparison = compare(key, node.entry.key)
            if (comparison == 0) return node.entry.value
            ref = if (comparison < 0) node.left else node.right
        }
        check(ref == null) { "Index height exceeds limit" }
        return null
    }

    /** Number of entries strictly less than key. */
    fun rank(root: String?, key: ByteArray, budget: Budget): Long {
        var ref = root
        var rank = 0L
        repeat(MAX_HEIGHT) {
            if (ref == null) return rank
            val node = read(requireNotNull(ref), budget)
            if (compare(key, node.entry.key) <= 0) ref = node.left else {
                rank = Math.addExact(rank, Math.addExact(count(node.left, budget), 1))
                ref = node.right
            }
        }
        check(ref == null) { "Index height exceeds limit" }
        return rank
    }

    fun count(root: String?, budget: Budget): Long = root?.let { read(it, budget).count } ?: 0

    fun select(root: String?, ordinal: Long, budget: Budget): Entry? {
        require(ordinal >= 0)
        var remaining = ordinal
        var ref = root
        repeat(MAX_HEIGHT) {
            if (ref == null) return null
            val node = read(requireNotNull(ref), budget)
            val left = count(node.left, budget)
            when {
                remaining < left -> ref = node.left
                remaining == left -> return node.entry
                else -> { remaining -= left + 1; ref = node.right }
            }
        }
        check(ref == null) { "Index height exceeds limit" }
        return null
    }

    fun put(root: String?, key: ByteArray, value: ByteArray, budget: Budget): String {
        require(key.size <= MAX_KEY && value.size <= MAX_VALUE)
        return requireNotNull(change(root, Entry(key.copyOf(), value.copyOf()), false, budget, 0))
    }

    fun delete(root: String?, key: ByteArray, budget: Budget): String? {
        require(key.size <= MAX_KEY)
        return change(root, Entry(key, byteArrayOf()), true, budget, 0)
    }

    private fun change(ref: String?, entry: Entry, delete: Boolean, budget: Budget, depth: Int): String? {
        check(depth < MAX_HEIGHT) { "Index height exceeds limit" }
        if (ref == null) return if (delete) null else node(entry, null, null, budget)
        val old = read(ref, budget)
        val comparison = compare(entry.key, old.entry.key)
        if (comparison < 0) {
            val left = change(old.left, entry, delete, budget, depth + 1)
            return if (left == old.left) ref else balance(old.entry, left, old.right, budget)
        }
        if (comparison > 0) {
            val right = change(old.right, entry, delete, budget, depth + 1)
            return if (right == old.right) ref else balance(old.entry, old.left, right, budget)
        }
        if (!delete) return if (entry.value.contentEquals(old.entry.value)) ref else node(entry, old.left, old.right, budget)
        if (old.left == null) return old.right
        if (old.right == null) return old.left
        val successor = requireNotNull(select(old.right, 0, budget))
        return balance(successor, old.left, change(old.right, successor, true, budget, depth + 1), budget)
    }

    private fun height(ref: String?, budget: Budget) = ref?.let { read(it, budget).height } ?: 0

    private fun balance(entry: Entry, left: String?, right: String?, budget: Budget): String {
        val delta = height(left, budget) - height(right, budget)
        if (delta > 1) {
            val child = read(requireNotNull(left), budget)
            if (height(child.left, budget) >= height(child.right, budget)) {
                return node(child.entry, child.left, node(entry, child.right, right, budget), budget)
            }
            val pivot = read(requireNotNull(child.right), budget)
            return node(pivot.entry, node(child.entry, child.left, pivot.left, budget),
                node(entry, pivot.right, right, budget), budget)
        }
        if (delta < -1) {
            val child = read(requireNotNull(right), budget)
            if (height(child.right, budget) >= height(child.left, budget)) {
                return node(child.entry, node(entry, left, child.left, budget), child.right, budget)
            }
            val pivot = read(requireNotNull(child.left), budget)
            return node(pivot.entry, node(entry, left, pivot.left, budget),
                node(child.entry, pivot.right, child.right, budget), budget)
        }
        return node(entry, left, right, budget)
    }

    private fun node(entry: Entry, left: String?, right: String?, budget: Budget): String {
        val l = left?.let { read(it, budget) }
        val r = right?.let { read(it, budget) }
        val height = maxOf(l?.height ?: 0, r?.height ?: 0) + 1
        check(height <= MAX_HEIGHT)
        val count = Math.addExact(Math.addExact(l?.count ?: 0, r?.count ?: 0), 1)
        val buffer = ByteBuffer.allocate(PAGE_BYTES)
        buffer.putInt(MAGIC).putInt(VERSION).putInt(height).putLong(count)
        buffer.put(refBytes(left)).put(refBytes(right))
        buffer.putInt(entry.key.size).putInt(entry.value.size).put(entry.key).put(entry.value)
        return store(buffer.array(), budget).also { if (staged != null) stagedPages.add(it) }
    }

    private fun read(ref: String, budget: Budget): Node {
        budget.visit()
        val bytes = load(ref, PAGE_BYTES)
        require(bytes.size == PAGE_BYTES)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == MAGIC && buffer.int == VERSION) { "Unsupported index page format" }
        val height = buffer.int
        val count = buffer.long
        require(height in 1..MAX_HEIGHT && count > 0)
        val left = readRef(buffer)
        val right = readRef(buffer)
        val keySize = buffer.int
        val valueSize = buffer.int
        require(keySize in 0..MAX_KEY && valueSize in 0..MAX_VALUE)
        val key = ByteArray(keySize).also(buffer::get)
        val value = ByteArray(valueSize).also(buffer::get)
        require((height == 1) == (left == null && right == null))
        require(buffer.remaining() >= 0 && (buffer.position() until PAGE_BYTES).all { bytes[it] == 0.toByte() })
        return Node(Entry(key, value), left, right, height, count)
    }

    /** Exact immutable object refs have no generation or ordinal dependence. Callers chunk bodies. */
    fun putObject(bytes: ByteArray, budget: Budget): String {
        require(bytes.size <= MAX_OBJECT)
        return store(bytes, budget)
    }

    fun objectBytes(ref: String, maxBytes: Int, budget: Budget): ByteArray {
        require(maxBytes in 0..MAX_OBJECT)
        budget.visit()
        return load(ref, maxBytes)
    }

    private fun store(bytes: ByteArray, budget: Budget): String {
        budget.write(bytes.size)
        work.encodedBytes += bytes.size
        val ref = hash(bytes)
        staged?.let {
            if (ref !in it) {
                check(bytes.size <= 64L * 1024 * 1024 - stagedBytes) { "Batch memory budget exceeded" }
                it[ref] = bytes.copyOf()
                stagedBytes += bytes.size
            }
            return ref
        }
        persist(ref, bytes)
        return ref
    }

    private fun persist(ref: String, bytes: ByteArray) {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory)
            syncDirectoryEntry(directory.parent)
        }
        val path = directory.resolve(ref)
        if (Files.exists(path)) {
            require(load(ref, bytes.size).contentEquals(bytes))
            return
        }
        val pending = directory.resolve("pending-${UUID.randomUUID()}")
        FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        work.fileSyncs++
        work.durableBytes += bytes.size
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load(ref: String, maxBytes: Int): ByteArray {
        require(REF.matches(ref)) { "Invalid immutable reference" }
        staged?.get(ref)?.let { require(it.size <= maxBytes); return it.copyOf() }
        FileChannel.open(directory.resolve(ref), StandardOpenOption.READ).use { channel ->
            require(channel.size() in 0..maxBytes.toLong()) { "Immutable object exceeds read budget" }
            val buffer = ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated immutable object" }
            return buffer.array().also { require(hash(it) == ref) { "Immutable object checksum mismatch" } }
        }
    }

    /** Caller holds the existing scoped transaction OS lock. No cancellation after atomic rename. */
    fun publish(roots: Roots, checkpoint: ByteArray, budget: Budget, beforePublish: () -> Unit = {}) {
        require(checkpoint.size <= MAX_CHECKPOINT)
        listOfNotNull(roots.order, roots.identity, roots.evidence, roots.tools, roots.unresolved).forEach { read(it, budget) }
        flushBatch(roots)
        val previous = activeRef()
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC); data.writeInt(HEAD_VERSION)
                for (ref in listOf(roots.order, roots.identity, roots.evidence, roots.tools, roots.unresolved, previous)) data.write(refBytes(ref))
                data.writeLong(roots.toolGeneration)
                data.writeInt(checkpoint.size); data.write(checkpoint)
            }
        }.toByteArray()
        val head = store(bytes, budget)
        syncDirectory()
        val pending = directory.resolve("head-${UUID.randomUUID()}")
        budget.write(64)
        FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(head.toByteArray(Charsets.US_ASCII))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        beforePublish()
        Files.move(pending, directory.resolve("active-v2"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory()
    }

    fun open(): Head? = activeRef()?.let(::openHead)

    fun openHead(ref: String): Head {
        val bytes = load(ref, MAX_CHECKPOINT + 212)
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Unsupported index head format" }
            val version = data.readInt()
            require(version == 2 || version == HEAD_VERSION) { "Unsupported index head format" }
            fun reference(): String? = readRef(ByteBuffer.wrap(ByteArray(32).also(data::readFully)))
            var roots = Roots(reference(), reference(), reference())
            if (version >= 3) roots = roots.copy(tools = reference(), unresolved = reference())
            val previous = reference()
            if (version >= 3) roots = roots.copy(toolGeneration = data.readLong().also { require(it >= 0) })
            val size = data.readInt()
            require(size in 0..MAX_CHECKPOINT && size == data.available())
            return Head(roots, ByteArray(size).also(data::readFully), previous)
        }
    }

    private fun activeRef(): String? {
        val path = directory.resolve("active-v2")
        if (!Files.exists(path)) return null // Explicit absence permits the caller's v1 fallback.
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            require(channel.size() == 64L) { "Corrupt v2 head; do not silently fall back to v1" }
            val buffer = ByteBuffer.allocate(64)
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
            return buffer.array().toString(Charsets.US_ASCII).also { require(REF.matches(it)) }
        }
    }

    private fun syncDirectory() = syncDirectoryEntry(directory)

    companion object {
        const val PAGE_BYTES = 4096
        const val MAX_KEY = 1024
        const val MAX_VALUE = 2048
        const val MAX_HEIGHT = 48
        const val MAX_OBJECT = 1024 * 1024
        private const val MAX_CHECKPOINT = 1024 * 1024
        private const val MAGIC = 0x4c544958
        private const val VERSION = 2
        private const val HEAD_VERSION = 3
        private val REF = Regex("[0-9a-f]{64}")
        private fun hash(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun refBytes(ref: String?): ByteArray {
            if (ref == null) return ByteArray(32)
            require(REF.matches(ref))
            return ByteArray(32) { ref.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        private fun readRef(buffer: ByteBuffer): String? {
            val bytes = ByteArray(32).also(buffer::get)
            return if (bytes.all { it == 0.toByte() }) null else java.util.HexFormat.of().formatHex(bytes)
        }
        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val delta = (a[i].toInt() and 255) - (b[i].toInt() and 255)
                if (delta != 0) return delta
            }
            return a.size.compareTo(b.size)
        }
    }
}
