package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginPackage
import com.letta.mobile.data.plugin.PluginRegistryState
import com.letta.mobile.data.plugin.PluginRuntime
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Where a host keeps its plugins on disk (letta-mobile-cdgpr.6): the registry at
 * `<root>/plugins.json`, written atomically, and each installed version unpacked at
 * `<root>/<id>/<version>/`. A version is unpacked into a staging sibling and moved into place
 * whole, so a crash mid-unpack never leaves a half-written package where the driver would run it.
 */
class PluginPackageStore(val root: Path) {
    val registryFile: Path = root.resolve(REGISTRY_FILE)

    /**
     * The registry as last saved; empty when there is none. A file that does not decode is moved
     * aside (`plugins.json.unreadable-<n>`) rather than overwritten, and the host starts empty.
     */
    fun load(): PluginRegistryState {
        val text = AtomicJsonFile.readOrNull(registryFile) ?: return PluginRegistryState()
        return try {
            PluginRegistryState.decode(text)
        } catch (e: SerializationException) {
            Files.move(registryFile, registryFile.resolveSibling("$REGISTRY_FILE.unreadable-${System.currentTimeMillis()}"))
            PluginHostEvents.record(PluginHostEvent.REGISTRY_UNREADABLE, "*", "error" to e.message.orEmpty())
            PluginRegistryState()
        }
    }

    fun save(state: PluginRegistryState) = AtomicJsonFile.write(registryFile, state.encode())

    /** The directory [manifest]'s version is unpacked in: the driver's package dir. */
    fun dirOf(manifest: PluginManifest): Path = root.resolve(manifest.id).resolve(manifest.version)

    /** Unpacks [pkg] to [dirOf] its manifest, replacing a leftover of the same version; answers the directory. */
    fun unpack(pkg: PluginPackage): Path {
        val target = dirOf(pkg.manifest)
        Files.createDirectories(target.parent)
        val staging = target.resolveSibling(".${target.fileName}.staging-${UUID.randomUUID()}")
        try {
            pkg.paths.forEach { path -> writeInside(staging, path, requireNotNull(pkg.file(path))) }
            markCommandExecutable(pkg.manifest, staging)
            if (Files.exists(target)) target.toFile().deleteRecursively()
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (Files.exists(staging)) staging.toFile().deleteRecursively()
        }
        return target
    }

    /** Deletes every unpacked version of [pluginId]. */
    fun delete(pluginId: String) {
        root.resolve(pluginId).toFile().deleteRecursively()
    }

    private fun writeInside(dir: Path, relative: String, bytes: ByteArray) {
        val file = dir.resolve(relative).normalize()
        require(file.startsWith(dir)) { "'$relative' leaves the package" }
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    /** Zip entries carry no mode bits; a path-qualified `runtime.command` must still be runnable. */
    private fun markCommandExecutable(manifest: PluginManifest, dir: Path) {
        val command = (manifest.runtime as? PluginRuntime.Process)?.command?.removePrefix("./") ?: return
        if ('/' !in command) return
        dir.resolve(command).normalize().takeIf { it.startsWith(dir) && Files.isRegularFile(it) }?.toFile()?.setExecutable(true, true)
    }

    companion object {
        const val REGISTRY_FILE: String = "plugins.json"
    }
}
