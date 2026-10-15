package com.letta.mobile.jni

import java.io.File
import java.io.OutputStream
import org.gradle.process.ExecOperations

/**
 * Runs [command] and returns its exit code, or -1 when it cannot be spawned (tool not installed).
 * Output goes to [output], or is discarded.
 */
internal fun ExecOperations.exitCode(command: List<String>, output: OutputStream = OutputStream.nullOutputStream()): Int =
    try {
        exec {
            commandLine(command)
            isIgnoreExitValue = true
            standardOutput = output
            errorOutput = output
        }.exitValue
    } catch (_: Exception) {
        -1
    }

/**
 * Finds llvm-readelf: `$ANDROID_NDK_HOME`, the known host NDK installs, any NDK under
 * `$ANDROID_HOME/ndk` (CI runners), then `llvm-readelf` / `readelf` on PATH.
 * Returns null with the tried candidates so the caller can fail loudly.
 */
internal class ReadelfLocator(private val execOperations: ExecOperations) {
    val ndkCandidates: List<String> by lazy { fixedCandidates() + sdkNdkCandidates() }

    fun locate(): String? =
        ndkCandidates.firstOrNull { File(it).let { f -> f.exists() && f.canExecute() } }
            ?: PATH_CANDIDATES.firstOrNull { execOperations.exitCode(listOf(it, "--version")) == 0 }

    private fun fixedCandidates(): List<String> {
        val ndkRoot = System.getenv("ANDROID_NDK_HOME") ?: ""
        return listOf(
            "$ndkRoot/$READELF",
            "$ndkRoot/$READELF.exe",
            "/opt/android-sdk/ndk/28.2.13676358/$READELF",
            "/opt/android-sdk/ndk/27.0.12077973/$READELF",
            "/opt/stacks/ndk-r26d/$READELF",
        )
    }

    private fun sdkNdkCandidates(): List<String> {
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: ""
        if (sdkRoot.isBlank()) return emptyList()
        return File("$sdkRoot/ndk").listFiles { f -> f.isDirectory }
            ?.map { "${it.absolutePath}/$READELF" }
            ?: emptyList()
    }

    private companion object {
        const val READELF = "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
        val PATH_CANDIDATES = listOf("llvm-readelf", "readelf")
    }
}
