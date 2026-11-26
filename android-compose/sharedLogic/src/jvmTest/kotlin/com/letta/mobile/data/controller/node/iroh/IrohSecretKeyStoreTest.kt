package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrohSecretKeyStoreTest {
    @Test
    fun fileBackedStoreReloadsStableIdentityBytes() = runTest {
        val dir = Files.createTempDirectory("iroh-key-store-stable").toFile()
        val path = java.io.File(dir, "identity.key").absolutePath
        val first = FileIrohSecretKeyStore(path).loadOrCreate()
        val second = FileIrohSecretKeyStore(path).loadOrCreate()

        assertEquals(IrohSecretKeyStores.KEY_BYTES, first.size)
        assertContentEquals(first, second)
    }

    @Test
    fun twoFreshStoresProduceDifferentIdentityBytes() = runTest {
        val dir = Files.createTempDirectory("iroh-key-store-different").toFile()
        val first = FileIrohSecretKeyStore(java.io.File(dir, "one.key").absolutePath).loadOrCreate()
        val second = FileIrohSecretKeyStore(java.io.File(dir, "two.key").absolutePath).loadOrCreate()

        assertFalse(first.contentEquals(second), "fresh stores must not share deterministic identity bytes")
    }

    @Test
    fun ephemeralStoreDoesNotUseTheOldDeterministicDevKey() = runTest {
        val oldDeterministicKey = ByteArray(32) { i -> (i + 1).toByte() }
        val generated = EphemeralIrohSecretKeyStore().loadOrCreate()

        assertEquals(IrohSecretKeyStores.KEY_BYTES, generated.size)
        assertFalse(generated.contentEquals(oldDeterministicKey), "ephemeral key must not be the old hardcoded dev key")
    }

    @Test
    fun theTempFileIsRwOwnerOnlyTheMomentItExistsOnPosix() {
        val dir = Files.createTempDirectory("iroh-owner-only-posix")
        assumeTrue("POSIX permissions only", "posix" in dir.fileSystem.supportedFileAttributeViews())
        var atCreation: String? = null

        IrohSecretKeyStores.writeOwnerOnly(dir.resolve("secret.json"), "secret") { temp ->
            atCreation = PosixFilePermissions.toString(Files.getPosixFilePermissions(temp))
        }

        assertEquals("rw-------", atCreation)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secret.json"))))
    }

    @Test
    fun theTempFileAclAllowsOnlyTheCurrentUserTheMomentItExistsOnWindows() {
        assumeTrue("Windows ACLs only", isWindowsHost())
        val dir = Files.createTempDirectory("iroh-owner-only-acl")
        var checked = false

        IrohSecretKeyStores.writeOwnerOnly(dir.resolve("secret.json"), "secret") { temp ->
            assertOnlyCurrentUserAllowed(temp)
            checked = true
        }

        assertTrue(checked, "the temp file was observed at creation")
        assertOnlyCurrentUserAllowed(dir.resolve("secret.json"))
    }

    @Test
    fun creationRestrictsTheFileAtCreationOnPosix() {
        val dir = Files.createTempDirectory("iroh-create-posix")
        assumeTrue("POSIX permissions only", "posix" in dir.fileSystem.supportedFileAttributeViews())
        val plain = Files.createFile(dir.resolve("plain"))
        assumeTrue(
            "the umask already narrows a plain create to rw-------, so the test could not tell the paths apart",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(plain)) != "rw-------",
        )

        val atCreation = createOwnerOnly(dir.resolve("secret"))

        assertTrue(atCreation, "the restriction was part of the creation")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("secret"))))
    }

    @Test
    fun creationRestrictsTheFileAtCreationOnWindows() {
        assumeTrue("Windows ACLs only", isWindowsHost())
        val dir = Files.createTempDirectory("iroh-create-acl")

        val atCreation = createOwnerOnly(dir.resolve("secret"))

        assertTrue(atCreation, "the ACL was part of the creation")
        assertOnlyCurrentUserAllowed(dir.resolve("secret"))
    }

    @Test
    fun anUnfindablePrincipalFallsBackToCreateThenRestrict() {
        assumeTrue("Windows ACLs only", isWindowsHost())
        val dir = Files.createTempDirectory("iroh-create-no-principal")

        val atCreation = createOwnerOnly(dir.resolve("secret")) { null }

        assertFalse(atCreation, "no principal, so the restriction came after creation")
        assertSingleAllowEntry(dir.resolve("secret"))
    }

    @Test
    fun aPrincipalOtherThanThisProcessFallsBackInsteadOfLockingItOut() {
        assumeTrue("Windows ACLs only", isWindowsHost())
        val dir = Files.createTempDirectory("iroh-create-other-principal")
        val other = runCatching {
            dir.fileSystem.userPrincipalLookupService.lookupPrincipalByName("NT AUTHORITY${'\\'}LOCAL SERVICE")
        }.getOrNull()
        assumeTrue("another account to name", other != null)

        val atCreation = createOwnerOnly(dir.resolve("secret")) { other }

        assertFalse(atCreation, "the file named an account this process is not, so it was recreated")
        assertSingleAllowEntry(dir.resolve("secret"))
        Files.write(dir.resolve("secret"), byteArrayOf(1))
    }

    @Test
    fun aFilesystemWithNeitherPosixNorAclsIsRefusedWithAMessageNamingTheDirectory() {
        val zip = Files.createTempDirectory("iroh-create-nofs").resolve("fs.zip")
        java.nio.file.FileSystems.newFileSystem(java.net.URI.create("jar:" + zip.toUri()), mapOf("create" to "true")).use { fs ->
            val views = fs.supportedFileAttributeViews()
            assumeTrue("a filesystem without posix or acl", "posix" !in views && "acl" !in views)
            Files.createDirectories(fs.getPath("/state"))

            val failure = assertFailsWith<IllegalArgumentException> { createOwnerOnly(fs.getPath("/state/secret")) }

            assertTrue("/state" in failure.message.orEmpty(), failure.message)
            assertTrue("neither POSIX permissions nor ACLs" in failure.message.orEmpty(), failure.message)
            assertFalse(Files.exists(fs.getPath("/state/secret")), "nothing was written")
        }
    }

    @Test
    fun theIdentityKeyFileIsOwnerOnlyAndLeavesNoTempFile() {
        val dir = Files.createTempDirectory("iroh-key-owner-only")
        val key = dir.resolve("identity.key")

        IrohSecretKeyStores.loadOrCreateFile(key.toString())

        assertEquals(listOf("identity.key"), Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() })
        if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key)))
        } else if (isWindowsHost()) {
            assertSingleAllowEntry(key)
        }
    }
}

/** True on Windows, where files carry ACLs instead of POSIX permission bits. */
internal fun isWindowsHost(): Boolean = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

/** Asserts [file]'s ACL is exactly one ALLOW entry, for the user running the test. */
internal fun assertOnlyCurrentUserAllowed(file: Path) {
    val entries = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl
    val user = file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))

    assertEquals(1, entries.size, entries.toString())
    assertEquals(AclEntryType.ALLOW, entries.single().type())
    assertEquals(user, entries.single().principal())
}

/** Asserts [file]'s ACL is exactly one ALLOW entry, whoever it names. */
internal fun assertSingleAllowEntry(file: Path) {
    val entries = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl

    assertEquals(1, entries.size, entries.toString())
    assertEquals(AclEntryType.ALLOW, entries.single().type())
}
