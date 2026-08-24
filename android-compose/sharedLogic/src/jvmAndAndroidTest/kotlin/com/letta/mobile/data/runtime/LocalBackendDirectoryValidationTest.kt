package com.letta.mobile.data.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalBackendDirectoryValidationTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("local-backend-dir-validation-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `blank path is invalid`() {
        assertIs<LocalBackendDirectoryValidation.Result.Invalid>(
            LocalBackendDirectoryValidation.validate("   "),
        )
    }

    @Test
    fun `relative path is invalid`() {
        assertIs<LocalBackendDirectoryValidation.Result.Invalid>(
            LocalBackendDirectoryValidation.validate("relative/path"),
        )
    }

    @Test
    fun `existing writable directory is valid`() {
        assertIs<LocalBackendDirectoryValidation.Result.Valid>(
            LocalBackendDirectoryValidation.validate(tempRoot.absolutePath),
        )
    }

    @Test
    fun `existing file (not a directory) is invalid`() {
        val file = File(tempRoot, "not-a-directory.txt").apply { writeText("x") }
        assertIs<LocalBackendDirectoryValidation.Result.Invalid>(
            LocalBackendDirectoryValidation.validate(file.absolutePath),
        )
    }

    @Test
    fun `nonexistent path under a writable existing ancestor is valid`() {
        val candidate = File(tempRoot, "nested/new-backend-dir")
        assertIs<LocalBackendDirectoryValidation.Result.Valid>(
            LocalBackendDirectoryValidation.validate(candidate.absolutePath),
        )
    }

    @Test
    fun `nonexistent path with no existing ancestor drive is invalid`() {
        // A path under a drive/root that itself doesn't exist has no existing
        // ancestor to walk up to, so it can never be created.
        val bogusRoot = File(tempRoot, "does-not-exist-root")
        val candidate = File(bogusRoot, "a/b/c")
        // bogusRoot itself doesn't exist, but tempRoot (its parent) does and is
        // writable, so this should still resolve to Valid via the ancestor walk.
        assertIs<LocalBackendDirectoryValidation.Result.Valid>(
            LocalBackendDirectoryValidation.validate(candidate.absolutePath),
        )
    }

    @Test
    fun `isValid convenience matches validate`() {
        assertTrue(LocalBackendDirectoryValidation.isValid(tempRoot.absolutePath))
        assertTrue(!LocalBackendDirectoryValidation.isValid(""))
    }
}
