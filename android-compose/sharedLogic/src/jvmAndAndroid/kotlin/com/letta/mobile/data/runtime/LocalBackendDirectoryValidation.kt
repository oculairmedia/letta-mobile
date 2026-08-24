package com.letta.mobile.data.runtime

import java.io.File

/**
 * Validates a candidate value for the desktop "local backend data directory"
 * setting — where the BUNDLED local letta-code runtime stores its agents,
 * conversations, and provider credentials (see `LocalRuntimeProviderAuthFile.kt`
 * and `DesktopLettaCodeRuntime.kt`'s `LETTA_LOCAL_BACKEND_DIR`).
 *
 * File IO (checking existence/writability, creating the directory) is
 * inherently platform-specific, so this lives in the `jvmAndAndroid` source
 * set rather than pure `commonMain` — both consumers (Android, desktop/JVM)
 * run on a JVM and share `java.io.File`. The desktop module owns the actual
 * folder-picker UI and persistence; this is just the validation rule, kept
 * here (not duplicated) per the module's cardinal rule.
 */
object LocalBackendDirectoryValidation {
    sealed interface Result {
        /** The path is usable as-is, or can be created as a directory. */
        data object Valid : Result

        /** The path cannot be used, with a user-facing [reason]. */
        data class Invalid(val reason: String) : Result
    }

    /**
     * Validates [path] as a local backend data directory:
     * - must be non-blank and absolute
     * - if it exists, must be a writable directory
     * - if it doesn't exist, its nearest existing ancestor must be a
     *   writable directory (so the path CAN be created on first use)
     */
    fun validate(path: String): Result {
        val trimmed = path.trim()
        if (trimmed.isBlank()) {
            return Result.Invalid("Path cannot be blank")
        }
        val file = File(trimmed)
        if (!file.isAbsolute) {
            return Result.Invalid("Path must be absolute")
        }
        return if (file.exists()) {
            validateExistingDirectory(file)
        } else {
            validateCreatableDirectory(file)
        }
    }

    private fun validateExistingDirectory(file: File): Result {
        if (!file.isDirectory) {
            return Result.Invalid("Path exists but is not a directory")
        }
        if (!file.canWrite()) {
            return Result.Invalid("Directory exists but is not writable")
        }
        return Result.Valid
    }

    private fun validateCreatableDirectory(file: File): Result {
        val nearestAncestor = generateSequence(file.parentFile) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: return Result.Invalid("No existing parent directory found for this path")
        if (!nearestAncestor.isDirectory) {
            return Result.Invalid("Parent path is not a directory")
        }
        if (!nearestAncestor.canWrite()) {
            return Result.Invalid("Cannot create this directory — its parent is not writable")
        }
        return Result.Valid
    }

    /** Convenience boolean form for call sites that don't need the reason. */
    fun isValid(path: String): Boolean = validate(path) is Result.Valid
}
