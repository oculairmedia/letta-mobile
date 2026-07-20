package com.letta.mobile.platform.root

import com.letta.mobile.di.IoDispatcher
import com.letta.mobile.platform.SystemAccessBuild
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.time.Duration.Companion.milliseconds
@Singleton
class SuRootShellBridge @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val auditLogger: RootShellAuditLogger,
) : RootShellBridge {
    @Volatile
    private var cachedAvailability: RootShellAvailability? = null

    override fun peekAvailability(): RootShellAvailability {
        if (!SystemAccessBuild.rootToolsEnabled) return unsupportedBuild()
        cachedAvailability?.let { return it }

        val knownPath = COMMON_SU_PATHS.firstOrNull { File(it).canExecute() }
        return if (knownPath != null) {
            RootShellAvailability(
                status = RootShellAvailabilityStatus.SuAvailable,
                providerHint = providerHintFromPath(knownPath),
                reason = "Found executable su provider at $knownPath. Grant still requires external root-manager approval.",
            ).also { cachedAvailability = it }
        } else {
            RootShellAvailability(
                status = RootShellAvailabilityStatus.NeedsDetection,
                reason = "Root bridge is compiled in. Run detection or request grant to verify an installed su provider.",
            )
        }
    }

    override suspend fun detect(): RootShellAvailability = withContext(ioDispatcher) {
        if (!SystemAccessBuild.rootToolsEnabled) return@withContext unsupportedBuild()

        val su = findSuExecutable() ?: return@withContext RootShellAvailability(
            status = RootShellAvailabilityStatus.SuUnavailable,
            reason = "No executable su provider was found on common Android paths or PATH.",
        ).also { cachedAvailability = it }

        val version = runPlainCommand(listOf(su, "-v"), DETECT_TIMEOUT_MS)
            ?: runPlainCommand(listOf(su, "--version"), DETECT_TIMEOUT_MS)
        val providerHint = providerHint(version.orEmpty(), su)

        RootShellAvailability(
            status = RootShellAvailabilityStatus.SuAvailable,
            providerHint = providerHint,
            reason = "Found su provider${providerHint?.let { " ($it)" }.orEmpty()}. Grant still requires external root-manager approval.",
        ).also { cachedAvailability = it }
    }

    override suspend fun requestGrant(timeoutMs: Long): RootShellGrantResult {
        val result = execute(
            RootShellCommandRequest(
                command = "id -u",
                timeoutMs = timeoutMs,
                outputLimitBytes = GRANT_OUTPUT_LIMIT_BYTES,
            ),
        )
        val uid = result.stdout.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        return RootShellGrantResult(
            granted = result.succeeded && uid == ROOT_UID,
            uid = uid,
            result = result,
        )
    }

    override suspend fun execute(request: RootShellCommandRequest): RootShellCommandResult = withContext(ioDispatcher) {
        val startMs = System.currentTimeMillis()
        if (RootShellCommandClassifier.requiresHumanApproval(request.command) && request.approvalId.isNullOrBlank()) {
            return@withContext auditedResult(
                request,
                RootShellCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    stdoutTruncated = false,
                    stderrTruncated = false,
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    errorMessage = "Root command requires explicit user approval before execution.",
                ),
            )
        }

        val availability = detect()
        if (availability.status != RootShellAvailabilityStatus.SuAvailable) {
            return@withContext auditedResult(
                request,
                RootShellCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    stdoutTruncated = false,
                    stderrTruncated = false,
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    providerHint = availability.providerHint,
                    errorMessage = availability.reason,
                ),
            )
        }

        val su = findSuExecutable() ?: return@withContext auditedResult(
            request,
            RootShellCommandResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                stdoutTruncated = false,
                stderrTruncated = false,
                timedOut = false,
                durationMs = System.currentTimeMillis() - startMs,
                providerHint = availability.providerHint,
                errorMessage = "su provider disappeared before command execution.",
            ),
        )

        val process = try {
            ProcessBuilder(su, "-c", request.toShellScript())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
                .also { runCatching { it.outputStream.close() } }
        } catch (t: Throwable) {
            return@withContext auditedResult(
                request,
                RootShellCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    stdoutTruncated = false,
                    stderrTruncated = false,
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    providerHint = availability.providerHint,
                    errorMessage = t.rootShellMessage(),
                ),
            )
        }

        try {
            coroutineScope {
                val stdout = async { process.inputStream.readLimited(request.outputLimitBytes) }
                val stderr = async { process.errorStream.readLimited(request.outputLimitBytes) }
                val completed = withTimeoutOrNull(request.timeoutMs) {
                    while (process.isAlive) {
                        if (process.waitFor(50, TimeUnit.MILLISECONDS)) return@withTimeoutOrNull true
                        delay(50.milliseconds)
                    }
                    true
                } == true

                if (!completed) {
                    process.destroyRootProcess()
                }

                val out = stdout.await()
                val err = stderr.await()
                val result = RootShellCommandResult(
                    exitCode = if (completed) process.exitValue() else null,
                    stdout = out.text,
                    stderr = err.text,
                    stdoutTruncated = out.truncated,
                    stderrTruncated = err.truncated,
                    timedOut = !completed,
                    durationMs = System.currentTimeMillis() - startMs,
                    providerHint = availability.providerHint,
                )
                auditedResult(request, result)
            }
        } catch (t: Throwable) {
            process.destroyRootProcess()
            if (t is CancellationException) throw t
            auditedResult(
                request,
                RootShellCommandResult(
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    stdoutTruncated = false,
                    stderrTruncated = false,
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    providerHint = availability.providerHint,
                    errorMessage = t.rootShellMessage(),
                ),
            )
        }
    }

    private suspend fun auditedResult(
        request: RootShellCommandRequest,
        result: RootShellCommandResult,
    ): RootShellCommandResult {
        auditLogger.record(request, result)
        return result
    }

    private fun findSuExecutable(): String? {
        COMMON_SU_PATHS.firstOrNull { File(it).canExecute() }?.let { return it }
        return runPlainCommand(listOf("sh", "-c", "command -v su"), DETECT_TIMEOUT_MS)
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun runPlainCommand(command: List<String>, timeoutMs: Long): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroyRootProcess()
            null
        } else if (process.exitValue() == 0) {
            process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private suspend fun InputStream.readLimited(limitBytes: Int): LimitedText = withContext(ioDispatcher) {
        val output = ByteArrayOutputStream(minOf(limitBytes, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        var kept = 0
        var truncated = false
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            val remaining = limitBytes - kept
            if (remaining > 0) {
                val toWrite = minOf(read, remaining)
                output.write(buffer, 0, toWrite)
                kept += toWrite
                if (toWrite < read) truncated = true
            } else {
                truncated = true
            }
        }
        LimitedText(output.toByteArray().toString(StandardCharsets.UTF_8), truncated)
    }

    private fun RootShellCommandRequest.toShellScript(): String = buildString {
        cwd?.let { append("cd ").append(shellQuote(it)).append(" || exit 127\n") }
        environment.forEach { (key, value) ->
            append("export ").append(key).append("=").append(shellQuote(value)).append('\n')
        }
        append("exec /system/bin/sh -c ").append(shellQuote(command))
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun Process.destroyRootProcess() {
        destroy()
        if (isAlive) {
            try {
                waitFor(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (isAlive) destroyForcibly()
    }

    private fun unsupportedBuild() = RootShellAvailability(
        status = RootShellAvailabilityStatus.UnsupportedBuild,
        reason = "Root tools are disabled by this build flavor.",
    )

    private data class LimitedText(val text: String, val truncated: Boolean)

    companion object {
        private const val DETECT_TIMEOUT_MS = 750L
        private const val GRANT_OUTPUT_LIMIT_BYTES = 4 * 1024
        private const val BUFFER_SIZE = 8 * 1024
        private const val ROOT_UID = "0"
        private val COMMON_SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/product/bin/su",
            "/apex/com.android.runtime/bin/su",
        )

        private fun providerHint(rawVersion: String, executable: String): String? {
            val normalized = rawVersion.lowercase()
            return when {
                "sukisu" in normalized -> "SukiSU"
                "kernelsu" in normalized || "ksu" in normalized -> "KernelSU"
                "magisk" in normalized -> "Magisk"
                "apatch" in normalized -> "APatch"
                rawVersion.isNotBlank() -> rawVersion.lineSequence().first().take(64)
                else -> providerHintFromPath(executable)
            }
        }

        private fun providerHintFromPath(path: String): String? = when {
            "ksu" in path.lowercase() -> "KernelSU-compatible"
            "magisk" in path.lowercase() -> "Magisk-compatible"
            "apatch" in path.lowercase() -> "APatch-compatible"
            else -> null
        }
    }
}
