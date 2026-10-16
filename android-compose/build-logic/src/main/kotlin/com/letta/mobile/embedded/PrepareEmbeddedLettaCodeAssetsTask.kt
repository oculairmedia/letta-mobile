package com.letta.mobile.embedded

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Builds the on-device letta-code tree (`letta-code/nodejs-project`): npm-installs the pinned
 * `@letta-ai/letta-code`, patches its minified `letta.js` for nodejs-mobile with the scripts in
 * `letta-code-runtime/patches/` (run in [LETTA_JS_PATCHES] order), and adds the runtime
 * preloads from `letta-code-runtime/runtime/`, an on-device npm and a package.json.
 */
@DisableCachingByDefault(because = "Runs npm install against the registry.")
abstract class PrepareEmbeddedLettaCodeAssetsTask @Inject constructor(
    private val fileSystem: FileSystemOperations,
) : DefaultTask() {
    /** `letta-code-runtime/patches`: letta.js patch scripts (inputs so an edited patch re-runs the task). */
    @get:InputDirectory
    abstract val patchesDirectory: DirectoryProperty

    /** `letta-code-runtime/runtime`: preloads shipped next to letta.js. */
    @get:InputDirectory
    abstract val runtimeDirectory: DirectoryProperty

    @get:Input
    abstract val lettaCodeVersion: Property<String>

    @get:Input
    abstract val deviceNpmVersion: Property<String>

    /** scripts/patch-embedded-letta-code-tool-groups.cjs (covered by testEmbeddedLettaCodeToolGroupPatch). */
    @get:Internal
    abstract val toolGroupPatchScript: RegularFileProperty

    @get:Internal
    abstract val npmWorkDirectory: DirectoryProperty

    @get:Internal
    abstract val npmStageDirectory: DirectoryProperty

    /** Directory npm runs from (the app project dir). */
    @get:Internal
    abstract val npmInvocationDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val npmWorkDir = npmWorkDirectory.get().asFile
        val assetRoot = outputDirectory.get().asFile.resolve("letta-code/nodejs-project")
        resetDirectory(npmWorkDir)
        resetDirectory(assetRoot)
        installNpmDependencies(npmWorkDir)

        val lettaJs = npmWorkDir.resolve("node_modules/@letta-ai/letta-code/letta.js")
        check(lettaJs.isFile) { "letta.js not found for unicode-property transform at ${lettaJs.absolutePath}" }
        // Patch scripts run from the npm work dir so bare imports (@babel/core) resolve there.
        fileSystem.copy {
            from(patchesDirectory)
            into(npmWorkDir)
        }
        LETTA_JS_PATCHES.forEach { patch -> runPatch(npmWorkDir, lettaJs, patch) }

        nodeCheck(npmWorkDir, lettaJs)
        stageDeviceNpm(npmWorkDir)
        copyNodeModules(npmWorkDir, assetRoot)
        fileSystem.copy {
            from(runtimeDirectory)
            into(assetRoot)
        }
        // Every preload the device passes to `node --require` must at least parse.
        assetRoot.listFiles { file -> file.name.endsWith(".cjs") }.orEmpty().forEach { nodeCheck(npmWorkDir, it) }
        writePackageJson(assetRoot)
    }

    private fun resetDirectory(dir: File) {
        fileSystem.delete { delete(dir) }
        dir.mkdirs()
    }

    private fun installNpmDependencies(npmWorkDir: File) {
        npmInstall(
            npmWorkDir,
            "npm install for embedded LettaCode failed.",
            "--ignore-scripts",
            "@letta-ai/letta-code@${lettaCodeVersion.get()}",
        )
        // babel + regexpu-core power patches/transform-unicode-property.mjs; regexpu-core also ships
        // for runtime/regexp-polyfill.cjs.
        npmInstall(
            npmWorkDir,
            "npm install for babel transform failed.",
            "@babel/core@7",
            "@babel/plugin-transform-unicode-property-regex@7",
            "regexpu-core@6",
        )
        installSharpWasm(npmWorkDir)
    }

    // sharp ships no android-arm64 native binary, so module load aborts with "Could not load the
    // sharp module using the android-arm64 runtime". sharp's own loader falls back to the wasm32
    // build when present — install it (version must match the sharp dependency in letta-code).
    private fun installSharpWasm(npmWorkDir: File) {
        // 0.34.x nests its dependencies under letta-code (npm-shrinkwrap.json); older releases
        // hoisted sharp to the root. Missing it must fail the build: without the wasm32 fallback
        // every on-device image send dies.
        val sharpPackageJson = SHARP_PACKAGE_JSON_PATHS.map(npmWorkDir::resolve).firstOrNull { it.isFile }
            ?: error("sharp not found under ${npmWorkDir.absolutePath} (looked in $SHARP_PACKAGE_JSON_PATHS)")
        val sharpVersion = checkNotNull((JsonSlurper().parse(sharpPackageJson) as Map<*, *>)["version"] as? String) {
            "sharp version missing in ${sharpPackageJson.absolutePath}"
        }
        npmInstall(
            npmWorkDir,
            "npm install for @img/sharp-wasm32 failed.",
            "--ignore-scripts",
            // npm refuses cross-cpu installs (EBADPLATFORM) even with --cpu=wasm32 on this npm
            // version; --force is the documented escape hatch for platform-package side-installs.
            "--force",
            "@img/sharp-wasm32@$sharpVersion",
        )
    }

    private fun runPatch(npmWorkDir: File, lettaJs: File, patch: LettaJsPatch) {
        when (patch) {
            is LettaJsPatch.Script ->
                run(npmWorkDir, patch.failure, listOf("node") + patch.nodeArgs + listOf(npmWorkDir.resolve(patch.file).absolutePath, lettaJs.absolutePath))
            LettaJsPatch.ToolGroups ->
                run(npmWorkDir, patch.failure, listOf("node", toolGroupPatchScript.get().asFile.absolutePath, lettaJs.absolutePath))
        }
    }

    // Ship npm so the on-device node can install packages. npm is pure JS (npm-cli.js) and runs on
    // the embedded node 18 runtime. Install it into a SEPARATE staging dir (installing npm into the
    // same tree it's part of confuses dedup), then copy node_modules/npm into the embedded tree. An
    // `npm` PATH wrapper (created at runtime) runs node npm-cli.js. See letta-mobile-iq24j.
    private fun stageDeviceNpm(npmWorkDir: File) {
        val npmStageDir = npmStageDirectory.get().asFile
        resetDirectory(npmStageDir)
        run(
            npmStageDir,
            "npm install for on-device npm failed.",
            listOf(npmCommand(), "install", "--no-audit", "--no-fund", "--ignore-scripts", "--prefix", npmStageDir.absolutePath, "npm@${deviceNpmVersion.get()}"),
        )
        val stagedNpm = npmStageDir.resolve("node_modules/npm")
        check(stagedNpm.resolve("bin/npm-cli.js").isFile) { "staged npm-cli.js missing at ${stagedNpm.absolutePath}" }
        fileSystem.copy {
            from(stagedNpm)
            into(npmWorkDir.resolve("node_modules/npm"))
        }
    }

    private fun copyNodeModules(npmWorkDir: File, assetRoot: File) {
        fileSystem.copy {
            from(npmWorkDir.resolve("node_modules"))
            into(assetRoot.resolve("node_modules"))
            // babel is a build-time tool only; don't ship it on device. regexpu-core (+ regenerate/
            // regjs*/unicode-* data) IS shipped: runtime/regexp-polyfill.cjs needs it for dynamically
            // constructed patterns that the static babel pass cannot reach.
            exclude("@babel/**", ".bin/**", "@jridgewell/**", "browserslist/**", "caniuse-lite/**", "electron-to-chromium/**")
            exclude(HOST_ONLY_NODE_MODULES)
        }
    }

    private fun writePackageJson(assetRoot: File) {
        assetRoot.resolve("package.json").writeText(
            """
            {
              "name": "letta-mobile-embedded-lettacode",
              "private": true,
              "type": "module",
              "dependencies": {
                "@letta-ai/letta-code": "${lettaCodeVersion.get()}"
              }
            }
            """.trimIndent(),
        )
    }

    private fun npmInstall(npmWorkDir: File, failure: String, vararg args: String) {
        val command = listOf(npmCommand(), "install", "--no-audit", "--no-fund", "--prefix", npmWorkDir.absolutePath) + args
        run(npmInvocationDirectory.get().asFile, failure, command)
    }

    private fun nodeCheck(npmWorkDir: File, file: File) {
        run(npmWorkDir, "${file.name} failed node --check.", listOf("node", "--check", file.absolutePath))
    }

    private fun run(workingDir: File, failure: String, command: List<String>) {
        val process = ProcessBuilder(command).directory(workingDir).inheritIO().start()
        check(process.waitFor() == 0) { failure }
    }

    private fun npmCommand(): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"
}

/** One step of the letta.js patch pipeline; see [LETTA_JS_PATCHES]. */
internal sealed interface LettaJsPatch {
    val failure: String

    /** A script from `letta-code-runtime/patches/`, run as `node <nodeArgs> <file> <letta.js>`. */
    data class Script(
        val file: String,
        override val failure: String,
        val nodeArgs: List<String> = emptyList(),
    ) : LettaJsPatch

    /** origin/main #478: keep tool-call groups intact during trimming (scripts/, has its own test). */
    data object ToolGroups : LettaJsPatch {
        override val failure = "Tool-call group trimming patch for letta.js failed."
    }
}

/** Order matters: the unicode transform must see the stock letta.js. */
internal val LETTA_JS_PATCHES: List<LettaJsPatch> = listOf(
    LettaJsPatch.Script(
        "transform-unicode-property.mjs",
        "Unicode property escape transform for letta.js failed.",
        nodeArgs = listOf("--max-old-space-size=8192"),
    ),
    LettaJsPatch.Script("patch-vision-model-input.mjs", "Vision-model-input transform for letta.js failed."),
    LettaJsPatch.Script("patch-image-url-shape.mjs", "image_url-shape transform for letta.js failed."),
    LettaJsPatch.ToolGroups,
)

/** Where sharp's package.json sits: nested under letta-code (0.34.x shrinkwrap) or hoisted. */
private val SHARP_PACKAGE_JSON_PATHS = listOf(
    "node_modules/@letta-ai/letta-code/node_modules/sharp/package.json",
    "node_modules/sharp/package.json",
)

/**
 * node_modules content that can never load under nodejs-mobile (process.platform == "android",
 * bionic libc), so shipping it only inflates the release APK. Every glob matches at any depth:
 * letta-code 0.34.x ships an npm-shrinkwrap.json, so its dependencies install NESTED under
 * `@letta-ai/letta-code/node_modules` and a root-anchored glob would miss them.
 *  - node-pty's prebuilds (no android build exists; the bundle falls back to child_process), its
 *    Windows-only conpty/winpty sources and its node-gyp build output.
 *  - sharp's per-platform binaries: sharp looks up `@img/sharp-android-<arch>`, misses, and falls
 *    back to `@img/sharp-wasm32`, which IS kept (see installSharpWasm). `@janhapke/sharp-electron`
 *    is a linux-x64 Electron build letta-code only loads on Linux desktops.
 *  - `@vscode/ripgrep-*` binaries: upstream skips ripgrep on android.
 *  - the orphan `letta-code/node_modules/@letta-ai/letta-code` tree the 0.34 shrinkwrap leaves
 *    (no package.json, nothing resolves into it).
 *  - TypeScript declaration files and source maps, which the runtime never reads.
 */
internal val HOST_ONLY_NODE_MODULES: List<String> = listOf(
    "**/node-pty/prebuilds/**",
    "**/node-pty/third_party/**",
    "**/node-pty/deps/**",
    "**/node-pty/build/**",
    "**/@img/sharp-libvips-linux*/**",
    "**/@img/sharp-linux*/**",
    "**/@img/sharp-libvips-darwin*/**",
    "**/@img/sharp-darwin*/**",
    "**/@img/sharp-win32*/**",
    "**/@janhapke/**",
    "**/@vscode/ripgrep-*/**",
    "**/@letta-ai/letta-code/node_modules/@letta-ai/letta-code/**",
    "**/*.d.ts",
    "**/*.d.mts",
    "**/*.d.cts",
    "**/*.map",
)
