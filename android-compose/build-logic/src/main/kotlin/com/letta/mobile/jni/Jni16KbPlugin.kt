package com.letta.mobile.jni

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

/**
 * `com.letta.mobile.jni-16kb` (letta-mobile-by4vm.71): the 16 KB LOAD-segment realignment and the
 * post-realignment ELF sanity check for the prebuilt Iroh / Nimbus JNI libraries vendored in
 * `app/src/rootDebug/jniLibs/`, formerly ~280 lines of app/build.gradle.kts. Task names, inputs,
 * outputs and wiring are unchanged.
 *
 * Why: libiroh_ffi.so (computer.iroh:iroh-android) and libjnidispatch.so (Nimbus JOSE/JWT) ship
 * with 4 KB ELF alignment, which Google Play rejects for apps targeting Android 15+
 * (developer.android.com/16kb-page-size). We have no source to relink with
 * `-Wl,-z,max-page-size=16384`, so `scripts/realign-16kb.py` lifts each PT_LOAD to the next 16 KB
 * offset in place (idempotent), and `scripts/so-sanity-check.sh` then refuses any .so whose
 * DT_HASH/DT_GNU_HASH/DT_STRTAB/DT_SYMTAB/DT_DYNAMIC were clobbered (fire #53: aligned, but
 * SIGSEGV at 0.79s because the linker could not read the hash table).
 *
 * Both run before every `merge*JniLibFolders` / `merge*NativeLibs` task, for every variant: the
 * Iroh transport needs these prebuilts regardless of the embedded-runtime flags.
 */
class Jni16KbPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val jniLibs = project.layout.projectDirectory.dir(JNI_LIBS_DIR)
            val soFiles = project.fileTree(jniLibs) { include("**/*.so") }
            val realign = project.tasks.register(REALIGN_TASK, Realign16KbJniLibsTask::class.java) {
                group = "build"
                description = "Rewrites prebuilt .so files under src/rootDebug/jniLibs/ for 16 KB LOAD alignment."
                notCompatibleWithConfigurationCache("Resolves Python from the host PATH at execution time.")
                script.set(project.layout.projectDirectory.file("scripts/realign-16kb.py"))
                jniLibsDirectory.set(jniLibs)
                sharedObjects.from(soFiles)
                realignedObjects.from(soFiles)
            }
            val verify = project.tasks.register(VERIFY_TASK, VerifyJniLibSanityTask::class.java) {
                group = "build"
                description = "Refuses to ship .so files missing DT_HASH/DT_GNU_HASH/DT_STRTAB/DT_SYMTAB (fire #53 crash)."
                notCompatibleWithConfigurationCache("Resolves llvm-readelf from the AGP NDK toolchain at execution time.")
                // Both tasks touch the same .so files, so Gradle cannot infer the order; without
                // this the configuration cache rejects the build ("implicit dependency").
                dependsOn(realign)
                script.set(project.layout.projectDirectory.file("scripts/so-sanity-check.sh"))
                jniLibsDirectory.set(jniLibs)
                // Inputs only: declaring the .so files as outputs here too formed a cycle with
                // realign16KbJniLibs, which the configuration cache rejects.
                sharedObjects.from(soFiles)
                reportDirectory.set(project.layout.buildDirectory.dir("verifyJniLibSanity-out"))
            }
            project.tasks.configureEach { if (isJniMergeTask()) dependsOn(realign, verify) }
        }
    }

    private fun Task.isJniMergeTask(): Boolean =
        name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))

    private companion object {
        const val JNI_LIBS_DIR = "src/rootDebug/jniLibs"
        const val REALIGN_TASK = "realign16KbJniLibs"
        const val VERIFY_TASK = "verifyJniLibSanity"
    }
}
