package com.letta.mobile.data.controller.node.iroh

/** Supplies the 32-byte private identity key used by an Iroh endpoint. */
fun interface IrohSecretKeyStore {
    suspend fun loadOrCreate(): ByteArray
}

class EphemeralIrohSecretKeyStore : IrohSecretKeyStore {
    override suspend fun loadOrCreate(): ByteArray = IrohSecretKeyStores.generateKeyBytes()
}

class FixedIrohSecretKeyStore(private val bytes: ByteArray) : IrohSecretKeyStore {
    override suspend fun loadOrCreate(): ByteArray {
        IrohSecretKeyStores.requireValidKey(bytes)
        return bytes.copyOf()
    }
}

class FileIrohSecretKeyStore(
    private val path: String,
) : IrohSecretKeyStore {
    override suspend fun loadOrCreate(): ByteArray = IrohSecretKeyStores.loadOrCreateFile(path)
}

object IrohSecretKeyStores {
    const val KEY_BYTES: Int = 32

    fun generateKeyBytes(): ByteArray = ByteArray(KEY_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    fun requireValidKey(bytes: ByteArray) {
        require(bytes.size == KEY_BYTES) { "iroh secret key must be $KEY_BYTES bytes, got ${bytes.size}" }
    }

    fun loadOrCreateFile(path: String): ByteArray {
        val file = java.io.File(path)
        if (file.exists()) {
            restrictKeyFilePermissions(file)
            return file.readBytes().also { requireValidKey(it) }
        }

        val generated = generateKeyBytes()
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile ?: java.io.File("."), ".${file.name}.tmp-${java.util.UUID.randomUUID()}")
        tmp.createNewFile()
        restrictKeyFilePermissions(tmp)
        tmp.writeBytes(generated)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        restrictKeyFilePermissions(file)
        com.letta.mobile.util.Telemetry.event("IrohNode", "secretKey.generated")
        return generated
    }

    fun restrictKeyFilePermissions(file: java.io.File) {
        val posix = runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            )
        }
        // letta-mobile-jvctq: no POSIX permissions (Windows): an ACL granting the owner alone.
        if (posix.isFailure && runCatching { restrictToOwnerAcl(file.toPath()) }.isFailure) {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
            file.setExecutable(false, false)
        }
    }

    /**
     * Writes [text] to [path] in a file only the host's user may read (POSIX `rw-------`, else an ACL
     * naming only the owner), the way the host keeps its secrets. The temp file is created owner-only
     * (see [createOwnerOnly]) before the text is written into it, and replaced by a rename; where neither
     * restriction applies this throws, so a secret is never written into a file others may read (unlike
     * [restrictKeyFilePermissions], there is no best-effort fallback). [onCreated] sees the temp file the
     * moment it exists, before anything is written into it.
     */
    internal fun writeOwnerOnly(path: java.nio.file.Path, text: String, onCreated: (java.nio.file.Path) -> Unit = {}) {
        path.parent?.let(java.nio.file.Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
        try {
            createOwnerOnly(temp)
            onCreated(temp)
            writeAndSync(temp, text.toByteArray(Charsets.UTF_8))
            runCatching {
                java.nio.file.Files.move(
                    temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse { java.nio.file.Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            restrictToOwnerOnly(path)
        } finally {
            java.nio.file.Files.deleteIfExists(temp)
        }
    }

    /**
     * Forces the directory entry of [path] to storage: a rename (the replacement [writeOwnerOnly] makes, or a file just created
     * in a directory) is durable only once its parent directory is synced as well. Best effort: a platform that cannot sync a
     * directory (Windows) or a failing sync is skipped, never an error. [writeOwnerOnly] itself could call this after its rename.
     */
    internal fun syncParentDirectory(path: java.nio.file.Path) {
        val parent = path.toAbsolutePath().parent ?: return
        if (!AtomicJsonFile.directorySyncSupported) return
        try {
            AtomicJsonFile.syncDirectory(parent)
        } catch (expected: java.io.IOException) {
            // Best effort: the data is still on its way to disk, only the entry's durability is not forced.
        } catch (expected: UnsupportedOperationException) {
            // Directory channels are not supported on every platform.
        }
    }

    /** Writes [bytes] into the existing [file] and forces them to storage, so a rename of it never exposes partial contents. */
    private fun writeAndSync(file: java.nio.file.Path, bytes: ByteArray) {
        java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /**
     * Creates the empty [file] so that only this user can open it from the moment it exists: on POSIX with a
     * `rw-------` attribute at creation (the umask can only narrow it), on an ACL filesystem (Windows) with a
     * one-entry ACL for the current user at creation. A provider that accepts neither attribute at creation (or a
     * current user that cannot be looked up) gets a plain create followed by [restrictToOwnerOnly]: for that short
     * window the still-empty file has the directory's default access. Where neither restriction applies at all this
     * throws, leaving the empty file for the caller to delete.
     */
    internal fun createOwnerOnly(file: java.nio.file.Path) {
        val views = file.fileSystem.supportedFileAttributeViews()
        val attribute: java.nio.file.attribute.FileAttribute<*>? = when {
            "posix" in views -> java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY_POSIX)
            "acl" in views -> currentUserOnlyAcl(file)
            else -> null
        }
        if (attribute != null) {
            try {
                java.nio.file.Files.createFile(file, attribute)
                return
            } catch (expected: UnsupportedOperationException) {
                // The provider cannot set this attribute at creation (checked before the file is made): restrict after.
            }
        }
        java.nio.file.Files.createFile(file)
        restrictToOwnerOnly(file)
    }

    private val OWNER_ONLY_POSIX = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")

    /** An `acl:acl` creation attribute granting the current user alone full control, or null when the user is not found. */
    private fun currentUserOnlyAcl(file: java.nio.file.Path): java.nio.file.attribute.FileAttribute<List<java.nio.file.attribute.AclEntry>>? {
        val user = runCatching {
            file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        }.getOrNull() ?: return null
        val entries = listOf(fullControlFor(user))
        return object : java.nio.file.attribute.FileAttribute<List<java.nio.file.attribute.AclEntry>> {
            override fun name(): String = "acl:acl"
            override fun value(): List<java.nio.file.attribute.AclEntry> = entries
        }
    }

    /** Owner-only access (POSIX `rw-------`, else an ACL naming only the owner), or a thrown error. */
    private fun restrictToOwnerOnly(file: java.nio.file.Path) {
        val posix = runCatching { java.nio.file.Files.setPosixFilePermissions(file, OWNER_ONLY_POSIX) }
        if (posix.isSuccess) return
        restrictToOwnerAcl(file)
    }

    /** Replaces [path]'s ACL with one entry: full control for its owner. Throws where ACLs are unsupported. */
    fun restrictToOwnerAcl(path: java.nio.file.Path) {
        val view = requireNotNull(
            java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView::class.java),
        ) { "no ACL view for $path" }
        view.acl = listOf(fullControlFor(view.owner))
    }

    private fun fullControlFor(principal: java.nio.file.attribute.UserPrincipal): java.nio.file.attribute.AclEntry =
        java.nio.file.attribute.AclEntry.newBuilder()
            .setType(java.nio.file.attribute.AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java))
            .build()
}
