package com.letta.mobile.data.controller.node.iroh

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
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
    fun theOwnerOnlyTempFileHasNoGroupOrOtherBitsWhenItIsCreated() {
        val dir = Files.createTempDirectory("owner-only-temp")
        assumePlainCreateIsWiderThanOwnerOnly(dir)
        val path = dir.resolve("secret.json")
        var atCreation: Set<PosixFilePermission>? = null

        IrohSecretKeyStores.writeOwnerOnly(path, "secret") { temp -> atCreation = Files.getPosixFilePermissions(temp) }

        // Read synchronously by the writer itself, between the creating call and anything else that touches the file.
        assertEquals(OWNER_ONLY, atCreation, "the temp file must be owner-only from the creating call, not after a later chmod")
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(path))
    }

    @Test
    fun aCreatedOwnerOnlyFileStartsWithoutGroupOrOtherBits() {
        val dir = Files.createTempDirectory("owner-only-create")
        assumePlainCreateIsWiderThanOwnerOnly(dir)
        val path = dir.resolve(".identity.key.tmp")

        IrohSecretKeyStores.createOwnerOnlyFile(path)

        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(path))
    }

    @Test
    fun aGeneratedIdentityKeyEndsOwnerOnly() = runTest {
        assumePosix()
        val path = Files.createTempDirectory("owner-only-key").resolve("identity.key")

        FileIrohSecretKeyStore(path.toString()).loadOrCreate()

        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(path))
    }

    @Test
    fun anExistingWiderFileAtTheDestinationEndsOwnerOnly() {
        assumePosix()
        val path = Files.createTempDirectory("owner-only-wider").resolve("secret.json")
        Files.writeString(path, "old")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"))

        IrohSecretKeyStores.writeOwnerOnly(path, "new")

        assertEquals("new", Files.readString(path))
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(path))
    }

    @Test
    fun aSymlinkPlantedAtTheDestinationIsReplacedNotFollowed() {
        assumePosix()
        val dir = Files.createTempDirectory("owner-only-dest-link")
        val victim = dir.resolve("victim.txt").also { Files.writeString(it, "victim") }
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-r--r--"))
        val path = Files.createSymbolicLink(dir.resolve("secret.json"), victim)

        IrohSecretKeyStores.writeOwnerOnly(path, "secret")

        assertFalse(Files.isSymbolicLink(path), "the rename replaces the link itself")
        assertEquals("secret", Files.readString(path))
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
        assertEquals("victim", Files.readString(victim), "nothing is written through the link")
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(victim))
    }

    @Test
    fun aSymlinkPlantedAtTheTempPathIsRefusedNotFollowed() {
        assumePosix()
        val dir = Files.createTempDirectory("owner-only-temp-link")
        val victim = dir.resolve("victim.txt").also { Files.writeString(it, "victim") }
        Files.setPosixFilePermissions(victim, PosixFilePermissions.fromString("rw-r--r--"))
        val temp = Files.createSymbolicLink(dir.resolve(".identity.key.tmp"), victim)

        assertFailsWith<FileAlreadyExistsException> { IrohSecretKeyStores.createOwnerOnlyFile(temp) }

        assertEquals("victim", Files.readString(victim))
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(victim))
    }

    /**
     * Windows only (skipped elsewhere): the written file ends with one ACL entry, for its owner. The temp file is
     * created with default security there, so its ACL at creation is not asserted; see the next test.
     */
    @Test
    fun onAclFileSystemsTheWrittenFileEndsWithOnlyTheOwnerEntry() {
        assumeAclWithoutPosix()
        val path = Files.createTempDirectory("owner-only-acl").resolve("secret.json")

        IrohSecretKeyStores.writeOwnerOnly(path, "secret")

        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        assertEquals(listOf(view.owner), view.acl.map { it.principal() })
        assertEquals("secret", Files.readString(path))
    }

    /**
     * Windows only (skipped elsewhere). Pins the reason the temp file is not created with an initial `acl:acl`
     * attribute: the directory's inheritable entries are merged into it anyway. If this fails, the JDK produced a
     * file with only the requested entry, and [IrohSecretKeyStores.createOwnerOnlyFile] should pass that attribute.
     */
    @Test
    fun onAclFileSystemsAnInitialAclStillReceivesTheDirectorysInheritableEntries() {
        assumeAclWithoutPosix()
        val dir = Files.createTempDirectory("owner-only-acl-inherit")
        val dirView = Files.getFileAttributeView(dir, AclFileAttributeView::class.java)
        val everyone = dir.fileSystem.userPrincipalLookupService.lookupPrincipalByGroupName("Everyone")
        dirView.acl = dirView.acl + AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone)
            .setPermissions(AclEntryPermission.READ_DATA)
            .setFlags(AclEntryFlag.FILE_INHERIT)
            .build()
        val ownerOnly = listOf(
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(dirView.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build(),
        )
        val initialAcl = object : FileAttribute<List<AclEntry>> {
            override fun name(): String = "acl:acl"
            override fun value(): List<AclEntry> = ownerOnly
        }
        val file = Files.createFile(dir.resolve("created"), initialAcl)

        val principals = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl.map { it.principal() }
        assertTrue(everyone in principals, "inherited entries were expected on the created file, got $principals")
    }

    /**
     * A plain create here must come out wider than owner-only (umask 022, the usual one), or a creation that ignores
     * the requested mode would look owner-only too and the creation-time assertions could not tell the two apart.
     */
    private fun assumePlainCreateIsWiderThanOwnerOnly(dir: Path) {
        assumePosix()
        val probe = Files.createFile(dir.resolve("plain-create-probe"))
        val plain = Files.getPosixFilePermissions(probe).also { Files.delete(probe) }
        assumeTrue("the umask already strips group and other bits: $plain", plain != OWNER_ONLY)
    }

    private fun assumePosix() = assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())

    private fun assumeAclWithoutPosix() {
        val views = FileSystems.getDefault().supportedFileAttributeViews()
        assumeTrue("acl" in views && "posix" !in views)
    }

    private companion object {
        val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    }
}
