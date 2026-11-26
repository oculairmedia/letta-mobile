package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

/** Creating and restricting files so that only the host's user can open them. */
internal object OwnerOnlyFiles {
    private val OWNER_ONLY_POSIX = PosixFilePermissions.fromString("rw-------")

    /**
     * Creates the empty [file] so that only this user can open it from the moment it exists, and returns whether that
     * held: true when the restriction was part of the creation itself (a `rw-------` attribute on POSIX, where the umask
     * can only narrow it; a one-entry ACL for the principal [lookupPrincipal] names on an ACL filesystem such as
     * Windows), false when the file was made plain and restricted afterwards, so that for a short window the still-empty
     * file had the directory's default access. That fallback is taken, and logged at WARN with its reason class, when
     * the provider rejects the attribute, the principal cannot be found, or the created file is not writable by this
     * process (the principal is not the account the process runs as). Where neither POSIX permissions nor ACLs exist this
     * throws [IllegalArgumentException] naming the directory, before any file is made; an empty file left behind by a later
     * failure is the caller's to delete.
     */
    fun createOwnerOnly(file: Path, lookupPrincipal: (Path) -> UserPrincipal? = ::currentUser): Boolean {
        val views = file.fileSystem.supportedFileAttributeViews()
        val attribute: FileAttribute<*>? = when {
            "posix" in views -> PosixFilePermissions.asFileAttribute(OWNER_ONLY_POSIX)
            "acl" in views -> lookupPrincipal(file)?.let(::aclAttributeFor)
            else -> throw IllegalArgumentException(unsupportedMessage(file))
        }
        if (attribute == null) return plainCreateThenRestrict(file, "principal_not_found")
        try {
            Files.createFile(file, attribute)
        } catch (expected: UnsupportedOperationException) {
            return plainCreateThenRestrict(file, "attribute_unsupported")
        }
        // An ACL naming an account other than this process's would lock this process out of its own file.
        if (!canWrite(file)) {
            Files.delete(file)
            return plainCreateThenRestrict(file, "principal_not_this_process")
        }
        return true
    }

    /** Owner-only access (POSIX `rw-------`, else an ACL naming only the owner), or a thrown error. */
    fun restrictToOwnerOnly(file: Path) {
        val posix = runCatching { Files.setPosixFilePermissions(file, OWNER_ONLY_POSIX) }
        if (posix.isSuccess) return
        restrictToOwnerAcl(file)
    }

    /** Replaces [path]'s ACL with one entry: full control for its owner. Throws where ACLs are unsupported. */
    fun restrictToOwnerAcl(path: Path) {
        val view = requireNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)) { unsupportedMessage(path) }
        view.acl = listOf(fullControlFor(view.owner))
    }

    private fun plainCreateThenRestrict(file: Path, reason: String): Boolean {
        Telemetry.event(
            "IrohNode", "ownerOnlyCreate.fallback", "reason" attr reason,
            level = Telemetry.Level.WARN,
        )
        Files.createFile(file)
        restrictToOwnerOnly(file)
        return false
    }

    private fun canWrite(file: Path): Boolean = try {
        Files.newByteChannel(file, java.nio.file.StandardOpenOption.WRITE).close()
        true
    } catch (expected: java.io.IOException) {
        false
    }

    private fun unsupportedMessage(path: Path): String {
        val directory = path.toAbsolutePath().parent ?: path
        return "Cannot keep a secret owner-only in $directory: its filesystem supports neither POSIX permissions nor ACLs, " +
            "so the file would be readable by other users"
    }
}

/** The account named by `user.name` on [file]'s filesystem, or null when it cannot be found. */
private fun currentUser(file: Path): UserPrincipal? = runCatching {
    file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
}.getOrNull()

/** An `acl:acl` creation attribute granting [user] alone full control. */
private fun aclAttributeFor(user: UserPrincipal): FileAttribute<List<AclEntry>> {
    val entries = listOf(fullControlFor(user))
    return object : FileAttribute<List<AclEntry>> {
        override fun name(): String = "acl:acl"
        override fun value(): List<AclEntry> = entries
    }
}

private fun fullControlFor(principal: UserPrincipal): AclEntry =
    AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(principal)
        .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
        .build()
