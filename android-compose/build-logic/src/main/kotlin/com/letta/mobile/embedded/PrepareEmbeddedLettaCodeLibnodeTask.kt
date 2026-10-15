package com.letta.mobile.embedded

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Downloads (SHA-256 pinned) and extracts the nodejs-mobile libnode archive. */
@DisableCachingByDefault(because = "Downloads a pinned archive; the local copy is reused when its SHA-256 matches.")
abstract class PrepareEmbeddedLettaCodeLibnodeTask : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:OutputFile
    abstract val archiveFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val archive = archiveFile.get().asFile
        archive.parentFile.mkdirs()
        val expected = expectedSha256.get()
        if (!archive.isFile || archive.sha256() != expected) {
            logger.lifecycle("[embedded-lettacode] downloading ${archive.name}")
            URI(archiveUrl.get()).toURL().openStream().use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val actualSha256 = archive.sha256()
        check(actualSha256 == expected) {
            "Unexpected SHA-256 for ${archive.name}: $actualSha256"
        }
        extract(archive, outputDirectory.get().asFile.canonicalFile)
    }

    private fun extract(archive: File, outputDir: File) {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = outputDir.resolve(entry.name).canonicalFile
                check(target.path == outputDir.path || target.path.startsWith(outputDir.path + File.separator)) {
                    "Archive entry escapes output directory: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
