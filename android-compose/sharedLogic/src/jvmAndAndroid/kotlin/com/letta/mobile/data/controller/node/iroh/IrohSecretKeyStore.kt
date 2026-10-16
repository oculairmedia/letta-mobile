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

    /** Replaces [path]'s ACL with one entry: full control for its owner. Throws where ACLs are unsupported. */
    fun restrictToOwnerAcl(path: java.nio.file.Path) {
        val view = requireNotNull(
            java.nio.file.Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView::class.java),
        ) { "no ACL view for $path" }
        view.acl = listOf(
            java.nio.file.attribute.AclEntry.newBuilder()
                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission::class.java))
                .build(),
        )
    }
}
