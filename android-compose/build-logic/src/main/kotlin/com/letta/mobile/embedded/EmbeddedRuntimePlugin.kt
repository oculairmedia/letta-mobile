package com.letta.mobile.embedded

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider

/**
 * `com.letta.mobile.embedded-runtime`: the on-device letta-code runtime for :app
 * (nodejs-mobile libnode + NDK helpers + the patched letta.js asset tree), formerly ~1,100 lines
 * of app/build.gradle.kts. Pins live in `letta-code-runtime/letta-code-runtime.properties` (the one
 * letta-code pin, shared with the desktop bundle and the wire contract), the letta.js patches in
 * `letta-code-runtime/patches/`, shipped preloads in `letta-code-runtime/runtime/`.
 *
 * Everything is gated by `embedLettaCodeNative` / `embedLettaCodeAssets` ([EmbeddedRuntimeFlags]),
 * which is also the release variant switch: on = `full` (arm64-v8a, runtime + LiteRT-LM), off =
 * `lite` (arm64-v8a/armeabi-v7a/x86_64, neither; CI and the default) where the tasks are registered but skipped and
 * only the EMBEDDED_LETTACODE_* BuildConfig fields are added. See docs/RELEASE.md.
 */
class EmbeddedRuntimePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") {
            val pins = EmbeddedRuntimePins.load(project, PINS_FILE)
            val flags = EmbeddedRuntimeFlags(project)
            val dirs = EmbeddedRuntimeDirs(project, pins)
            val nativeTasks = registerNativeTasks(project, pins, flags, dirs)
            val assetsTask = registerAssetTasks(project, pins, flags, dirs)
            if (flags.assetsEnabled.get()) wireAssetTasks(project, assetsTask)
            if (flags.nativeEnabled.get()) wireNativeTasks(project, nativeTasks)
            configureEmbeddedRuntimeAndroid(project, pins, flags, dirs)
        }
    }

    private fun registerNativeTasks(
        project: Project,
        pins: EmbeddedRuntimePins,
        flags: EmbeddedRuntimeFlags,
        dirs: EmbeddedRuntimeDirs,
    ): List<TaskProvider<out Task>> {
        val libnode = project.tasks.register("prepareEmbeddedLettaCodeLibnode", PrepareEmbeddedLettaCodeLibnodeTask::class.java) {
            onlyIf { flags.nativeEnabled.get() }
            notCompatibleWithConfigurationCache("Downloads and extracts the Node.js Mobile native archive.")
            archiveUrl.set(pins.libnodeUrl)
            expectedSha256.set(pins.libnodeSha256)
            archiveFile.set(dirs.libnodeArchive)
            outputDirectory.set(dirs.libnode)
        }
        val curl = registerNdkHelper(project, flags, CURL_HELPER)
        val bash = registerNdkHelper(project, flags, BASH_HELPER)
        val git = registerNdkHelper(project, flags, GIT_HELPER)
        val nodeCli = registerNdkHelper(project, flags, NODE_CLI_HELPER)
        nodeCli.configure {
            dependsOn(libnode)
            inputs.dir(dirs.libnode)
        }
        return listOf(libnode, bash, git, curl, nodeCli)
    }

    private fun registerNdkHelper(
        project: Project,
        flags: EmbeddedRuntimeFlags,
        helper: NdkHelper,
    ): TaskProvider<Exec> = project.tasks.register(helper.taskName, Exec::class.java) {
        onlyIf { flags.nativeEnabled.get() }
        // The scripts call the local NDK toolchain directly; nothing about them is cache-safe.
        notCompatibleWithConfigurationCache(helper.notCompatibleReason)
        val script = project.layout.projectDirectory.file("../../scripts/${helper.script}")
        inputs.file(script)
        helper.source?.let { inputs.file(project.layout.projectDirectory.file(it)) }
        outputs.file(project.layout.projectDirectory.file(helper.output))
        workingDir(project.rootProject.projectDir.parentFile)
        commandLine("bash", script.asFile.absolutePath)
    }

    private fun registerAssetTasks(
        project: Project,
        pins: EmbeddedRuntimePins,
        flags: EmbeddedRuntimeFlags,
        dirs: EmbeddedRuntimeDirs,
    ): TaskProvider<PrepareEmbeddedLettaCodeAssetsTask> {
        val repoScripts = project.rootDir.parentFile.resolve("scripts")
        val assets = project.tasks.register("prepareEmbeddedLettaCodeAssets", PrepareEmbeddedLettaCodeAssetsTask::class.java) {
            onlyIf { flags.assetsEnabled.get() }
            notCompatibleWithConfigurationCache("Runs npm install and copies embedded LettaCode assets.")
            patchesDirectory.set(project.rootProject.layout.projectDirectory.dir("$RUNTIME_DIR/patches"))
            runtimeDirectory.set(project.rootProject.layout.projectDirectory.dir("$RUNTIME_DIR/runtime"))
            lettaCodeVersion.set(pins.lettaCodeVersion)
            deviceNpmVersion.set(pins.deviceNpmVersion)
            toolGroupPatchScript.set(repoScripts.resolve("patch-embedded-letta-code-tool-groups.cjs"))
            npmWorkDirectory.set(project.layout.buildDirectory.dir("embedded-lettacode/npm"))
            npmStageDirectory.set(project.layout.buildDirectory.dir("embedded-lettacode/npm-stage"))
            npmInvocationDirectory.set(project.layout.projectDirectory)
            outputDirectory.set(dirs.assets)
        }
        val toolGroupTest = project.tasks.register("testEmbeddedLettaCodeToolGroupPatch", Exec::class.java) {
            description = "Runs regression tests for embedded LettaCode tool-call group trimming."
            group = "verification"
            commandLine("node", repoScripts.resolve("patch-embedded-letta-code-tool-groups.test.cjs").absolutePath)
        }
        project.tasks.named("check") { dependsOn(toolGroupTest) }
        return assets
    }

    private fun wireAssetTasks(project: Project, assets: TaskProvider<PrepareEmbeddedLettaCodeAssetsTask>) {
        project.tasks.configureEach {
            // Release builds run lint-vital, whose model/analyze tasks read the merged-assets output
            // (which includes the embedded LettaCode assets). Gradle's strict task-dependency
            // validation fails the build unless they also depend on the asset prep.
            if (needsEmbeddedAssets(name)) {
                dependsOn(assets)
            }
        }
        // Catch late-instantiated tasks using matching, as whenTaskAdded breaks eager task
        // evaluation in AGP 8.x+.
        project.tasks.matching { isReleaseLintVital(it.name) }.configureEach { dependsOn(assets) }
    }

    private fun wireNativeTasks(project: Project, nativeTasks: List<TaskProvider<out Task>>) {
        project.tasks.configureEach {
            if (needsEmbeddedNativeLibs(name)) {
                nativeTasks.forEach { dependsOn(it) }
            }
        }
    }

    private fun needsEmbeddedAssets(name: String): Boolean = isMergeTask(name, "Assets") || isReleaseLintVital(name)

    private fun needsEmbeddedNativeLibs(name: String): Boolean =
        name.contains("CMake") || isMergeTask(name, "NativeLibs") || isMergeTask(name, "JniLibFolders")

    private fun isMergeTask(name: String, suffix: String): Boolean = name.startsWith("merge") && name.endsWith(suffix)

    private fun isReleaseLintVital(name: String): Boolean =
        (name.startsWith("lintVital") || (name.startsWith("generate") && name.contains("LintVital"))) &&
            name.contains("Release")

    /** An NDK-built helper binary: `bash scripts/<script>` produces `<output>` (gitignored). */
    private data class NdkHelper(
        val taskName: String,
        val notCompatibleReason: String,
        val script: String,
        val output: String,
        val source: String? = null,
    )

    private companion object {
        /** Relative to the Gradle root (android-compose/). */
        const val RUNTIME_DIR = "letta-code-runtime"
        const val PINS_FILE = "$RUNTIME_DIR/letta-code-runtime.properties"

        val CURL_HELPER = NdkHelper(
            "prepareEmbeddedCurlHelper",
            "Builds the Android curl helper with the local NDK toolchain.",
            "build-android-curl.sh",
            "libs/embedded-curl/arm64-v8a/libcurl.so",
            "src/main/cpp/embedded_curl_bridge.c",
        )
        val BASH_HELPER = NdkHelper(
            "prepareEmbeddedBashHelper",
            "Builds GNU bash for android-arm64 with the local NDK toolchain.",
            "build-android-bash.sh",
            "libs/embedded-bash/arm64-v8a/libbash.so",
        )
        val GIT_HELPER = NdkHelper(
            "prepareEmbeddedGitHelper",
            "Builds git for android-arm64 with the local NDK toolchain.",
            "build-android-git.sh",
            "libs/embedded-git/arm64-v8a/libgit.so",
        )
        val NODE_CLI_HELPER = NdkHelper(
            "prepareEmbeddedNodeCli",
            "Builds the Android node CLI wrapper against nodejs-mobile libnode.",
            "build-android-node-cli.sh",
            "libs/embedded-node-cli/arm64-v8a/libnodecli.so",
            "src/main/cpp/embedded_node_cli.cpp",
        )
    }
}

/** Generated locations under app/build, shared by the tasks and the Android wiring. */
internal class EmbeddedRuntimeDirs(project: Project, pins: EmbeddedRuntimePins) {
    val assets: Provider<Directory> = project.layout.buildDirectory.dir("generated/embedded-lettacode-assets")
    val libnode: Provider<Directory> = project.layout.buildDirectory.dir("generated/embedded-lettacode-libnode")
    val libnodeArchive: Provider<RegularFile> =
        project.layout.buildDirectory.file("embedded-lettacode/libnode/${pins.libnodeArchiveName}")
}
