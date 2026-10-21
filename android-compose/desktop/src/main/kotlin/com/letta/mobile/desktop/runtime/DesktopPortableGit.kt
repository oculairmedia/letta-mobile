package com.letta.mobile.desktop.runtime

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Pinned MinGit (letta-mobile-k5nbq.8, G07). SHA-256 from the Git for Windows 2.51.0 announcement.
 * Extracted under `~/.letta-mobile/portable-git/<version>/`; `cmd` is prepended to the runtime PATH.
 */
internal data class PortableGitRelease(
    val version: String,
    val url: String,
    val sha256: String,
) {
    companion object {
        val Current = PortableGitRelease(
            version = "2.51.0",
            url = "https://github.com/git-for-windows/git/releases/download/v2.51.0.windows.1/MinGit-2.51.0-64-bit.zip",
            sha256 = "c2c955a21fa99889d83f485f24fa5d9a38fffc2d509d4022385510e11c26b250",
        )
    }
}

internal fun portableGitRoot(home: File = File(System.getProperty("user.home"))): File =
    File(home, ".letta-mobile/portable-git")

internal fun portableGitCmdDirectory(
    home: File = File(System.getProperty("user.home")),
    release: PortableGitRelease = PortableGitRelease.Current,
): File = File(portableGitRoot(home), "${release.version}/cmd")

/**
 * Downloads [release], refuses a SHA-256 mismatch, extracts `cmd/git.exe`. [download] and [digest]
 * are faked in tests so the pin is exercised without the network.
 *
 * letta-mobile-k5nbq.13: the archive is downloaded and unpacked in a staging directory beside the
 * versioned one and renamed into place only once complete, so neither a crash mid-extract nor the
 * git availability poll ever sees a half tree under `<version>/cmd`. Staging is removed on any
 * failure, including a checksum mismatch.
 */
internal class PortableGitInstaller(
    private val root: File,
    private val release: PortableGitRelease = PortableGitRelease.Current,
    private val download: (String, File) -> Unit = ::downloadUrlTo,
    private val digest: (File) -> String = ::sha256HexOf,
) {
    fun cmdDirectory(): File = File(root, "${release.version}/cmd")

    fun install(): File {
        val dest = File(root, release.version)
        val git = File(dest, GIT_EXE)
        if (git.isFile) return git.parentFile
        dest.deleteRecursively()
        root.mkdirs()
        val zip = Files.createTempFile(root.toPath(), ".${release.version}-", ".zip").toFile()
        val staging = Files.createTempDirectory(root.toPath(), ".${release.version}-").toFile()
        try {
            download(release.url, zip)
            verifyChecksum(zip)
            extractZip(zip, staging)
            check(File(staging, GIT_EXE).isFile) { "PortableGit extract did not contain cmd/git.exe" }
            Files.move(staging.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
        return git.parentFile
    }

    private fun verifyChecksum(zip: File) {
        val actual = digest(zip)
        check(actual.equals(release.sha256, ignoreCase = true)) {
            "PortableGit checksum mismatch (expected ${release.sha256}, got $actual)"
        }
    }
}

/** Downloads [url] to [dest], failing instead of hanging when the server stalls. */
internal fun downloadUrlTo(url: String, dest: File) {
    dest.parentFile?.mkdirs()
    val connection = URI(url).toURL().openConnection().apply {
        connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        readTimeout = DOWNLOAD_READ_TIMEOUT_MS
    }
    connection.getInputStream().use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
}

internal fun sha256HexOf(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        val hex = (byte.toInt() and 0xff).toString(16)
        if (hex.length == 1) "0$hex" else hex
    }
}

internal fun extractZip(zip: File, dest: File) {
    var unpacked = 0L
    ZipInputStream(zip.inputStream()).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: break
            if (!entry.isDirectory) unpacked += writeZipEntry(stream, dest, entry.name, unpacked)
        }
    }
}

private fun writeZipEntry(stream: ZipInputStream, dest: File, name: String, already: Long): Long {
    check(!isUnsafeZipPath(name)) { "PortableGit zip entry escapes the extract directory: $name" }
    val out = File(dest, name.replace('/', File.separatorChar))
    out.parentFile?.mkdirs()
    var written = 0L
    out.outputStream().use { file ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            written += read
            check(already + written <= MAX_UNPACKED_BYTES) { "PortableGit unpack exceeded $MAX_UNPACKED_BYTES bytes" }
            file.write(buffer, 0, read)
        }
    }
    return written
}

/** Absolute, parent-escaping, drive-relative (`C:foo`) or alternate-data-stream (`a:b`) entry names. */
internal fun isUnsafeZipPath(name: String): Boolean {
    val normalized = name.replace('\\', '/')
    return normalized.startsWith("/") || ':' in normalized || normalized.split('/').any { it == ".." }
}

private const val GIT_EXE = "cmd/git.exe"
private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
private const val HASH_BUFFER_BYTES = 64 * 1024
private const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024
