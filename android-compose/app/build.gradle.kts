import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("io.github.takahirom.roborazzi")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("io.sentry.android.gradle")
    id("androidx.baselineprofile")
    id("org.jetbrains.kotlinx.kover") // version inherited from root
}

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

sentry {
    telemetry.set(false)
    autoInstallation.enabled.set(true)

    // Gate ProGuard mapping + source-context upload on the auth token being
    // present. sentry-cli needs SENTRY_AUTH_TOKEN + SENTRY_ORG + SENTRY_PROJECT
    // (and SENTRY_URL for our self-hosted instance at sentry.oculair.ca) to
    // push symbols. Without them, the upload tasks abort with
    // "An organization ID or slug is required" and fail the build. Gating
    // lets local builds (no secrets) and fork PRs succeed, while main-branch
    // CI on the oculairmedia/letta-mobile repo (which has the secrets) still
    // ships mapping files so stack traces stay deobfuscated.
    val hasSentryAuth = providers.environmentVariable("SENTRY_AUTH_TOKEN").orNull?.isNotBlank() == true
    includeProguardMapping.set(hasSentryAuth)
    includeSourceContext.set(hasSentryAuth)

    // Org / project / URL come from the environment (populated by CI from
    // repo secrets — see .github/workflows/android.yml). Hard-defaulting
    // here would lock us to a single Sentry instance; env-driven keeps the
    // build portable.
    providers.environmentVariable("SENTRY_ORG").orNull?.takeIf { it.isNotBlank() }?.let { org.set(it) }
    providers.environmentVariable("SENTRY_PROJECT").orNull?.takeIf { it.isNotBlank() }?.let { projectName.set(it) }
    providers.environmentVariable("SENTRY_URL").orNull?.takeIf { it.isNotBlank() }?.let { url.set(it) }

    tracingInstrumentation {
        enabled.set(true)
    }
}

val keystorePropsFile = rootProject.file("keystore.properties")
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        load(localPropsFile.inputStream())
    }
}

// ───────────────────────── Tag-driven versioning ─────────────────────────
//
// versionName comes from the git tag (`vX.Y.Z`) when CI builds a tagged
// release. Local / PR / untagged-main builds fall back to `git describe`
// (e.g. `0.1.0-3-gab12cd-dirty`). When no v* tag exists yet the fallback
// is `0.0.0-dev`.
//
// versionCode is derived from versionName via M*10000 + m*100 + p, so:
//   0.1.0 → 100      1.2.6 → 10206      2.0.0 → 20000
// Caps comfortably under the Play Store int limit (2_147_483_647).
//
// Override either at build time:
//   ./gradlew assembleRelease -PversionNameOverride=1.2.3-rc1
//
// Release procedure:
//   1. git tag -a v0.1.0 -m "release: 0.1.0"
//   2. git push origin v0.1.0
//   3. .github/workflows/release.yml picks it up, builds the signed APK,
//      attaches it to the GitHub Release.

@Suppress("UnstableApiUsage")
fun computeVersionName() = providers.provider {
    // 1. Explicit override (handy for one-off builds).
    providers.gradleProperty("versionNameOverride").orNull?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable("GITHUB_REF_NAME").orNull
            ?.takeIf { it.startsWith("v") }
            ?.removePrefix("v")
            ?.takeIf { Regex("""\d+\.\d+\.\d+.*""").matches(it) }
}
    .orElse(
        // 3. Fallback: `git describe`. Reports e.g. `0.1.0-3-gab12cd` on a
        //    branch 3 commits past v0.1.0, or `0.1.0-3-gab12cd-dirty` with
        //    uncommitted changes. If no tags exist yet, returns `0.0.0-dev`.
        providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty", "--match", "v[0-9]*")
            workingDir = rootProject.projectDir.parentFile ?: rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.map { output ->
            output.trim()
                .removePrefix("v")
                .takeIf { it.isNotBlank() && it.matches(Regex("""\d+\.\d+\.\d+.*""")) }
                ?: "0.0.0-dev"
        },
    )

fun computeVersionCode(versionName: String): Int {
    // Strip any suffix (`-rc.1`, `-3-gab12cd`, `-dirty`) — only the
    // M.m.p portion drives the integer.
    val clean = versionName.substringBefore('-').substringBefore('+').trim()
    val parts = clean.split('.').map { it.toIntOrNull() ?: 0 }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    // Android rejects versionCode <= 0. Floor at 1 so the `0.0.0-dev`
    // fallback (no v* tag yet, or a shallow checkout that can't reach
    // any tag) still produces a valid Android build.
    return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
}

val computedVersionName = computeVersionName().get() ?: "0.0.0-dev"
val computedVersionCode = computeVersionCode(computedVersionName)

logger.lifecycle("[versioning] versionName=$computedVersionName versionCode=$computedVersionCode")

val embeddedLettaCodeVersion = "0.26.1"
val embeddedLettaCodeIntegrity = "sha512-vI+UU6ZNyTLtKFqhvr5+AyGXj1/sF5oggjgwB6Q0y0t/Y6FaytIlzKhus/P9/LtziXZdbZmqItMGEbYSXk2/CQ=="
// Bump when asset-prep transforms change (transpile/polyfill), so the on-device
// extractor re-extracts even though the npm version is unchanged.
val embeddedLettaCodeAssetRevision = "30"
val embeddedLettaCodeLibnodeVersion = "v18.20.4"
val embeddedLettaCodeLibnodeSha256 = "bd7321eaa1a7602fbe0bb87302df2d79d87835cf4363fbdd17c350dbb485c2af"
val embeddedLettaCodeLibnodeArchiveName = "nodejs-mobile-$embeddedLettaCodeLibnodeVersion-android.zip"
val embeddedLettaCodeLibnodeUrl =
    "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/$embeddedLettaCodeLibnodeVersion/$embeddedLettaCodeLibnodeArchiveName"
// Embedded-runtime opt-in resolves in this order:
//   1. -PembedLettaCodeNative=<bool>  (explicit; CI/release pass false)
//   2. local.properties `embedLettaCodeNative=true` (machine-local default —
//      gitignored, so dev machines can always build the embedded runtime into
//      debug APKs without burdening CI, which never sets it)
//   3. false
val embedNativeLocalDefault = (localProps.getProperty("embedLettaCodeNative") ?: "")
    .equals("true", ignoreCase = true)
val embedAssetsLocalDefault = (localProps.getProperty("embedLettaCodeAssets") ?: "")
    .equals("true", ignoreCase = true)
val embeddedLettaCodeNativeEnabled = providers.gradleProperty("embedLettaCodeNative")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(embedNativeLocalDefault)
val embeddedLettaCodeAssetsEnabled = providers.gradleProperty("embedLettaCodeAssets")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(
        providers.provider { localProps.getProperty("embedLettaCodeAssets") }
            .map { it.equals("true", ignoreCase = true) }
            .orElse(embeddedLettaCodeNativeEnabled)
    )
val embeddedLettaCodeAssetsDir = layout.buildDirectory.dir("generated/embedded-lettacode-assets")
val embeddedLettaCodeLibnodeDir = layout.buildDirectory.dir("generated/embedded-lettacode-libnode")
val embeddedLettaCodeLibnodeArchive = layout.buildDirectory.file("embedded-lettacode/libnode/$embeddedLettaCodeLibnodeArchiveName")

fun npmCommand(): String =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npm.cmd" else "npm"

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

        val outputDir = outputDirectory.get().asFile.canonicalFile
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

val prepareEmbeddedLettaCodeLibnode = tasks.register<PrepareEmbeddedLettaCodeLibnodeTask>(
    "prepareEmbeddedLettaCodeLibnode",
) {
    onlyIf { embeddedLettaCodeNativeEnabled.get() }
    notCompatibleWithConfigurationCache("Downloads and extracts the Node.js Mobile native archive.")
    archiveUrl.set(embeddedLettaCodeLibnodeUrl)
    expectedSha256.set(embeddedLettaCodeLibnodeSha256)
    archiveFile.set(embeddedLettaCodeLibnodeArchive)
    outputDirectory.set(embeddedLettaCodeLibnodeDir)
}

val prepareEmbeddedCurlHelper = tasks.register<Exec>("prepareEmbeddedCurlHelper") {
    onlyIf { embeddedLettaCodeNativeEnabled.get() }
    notCompatibleWithConfigurationCache("Builds the Android curl helper with the local NDK toolchain.")
    val script = project.layout.projectDirectory.file("../../scripts/build-android-curl.sh")
    val source = project.layout.projectDirectory.file("src/main/cpp/embedded_curl_bridge.c")
    val output = project.layout.projectDirectory.file("libs/embedded-curl/arm64-v8a/libcurl.so")
    inputs.file(script)
    inputs.file(source)
    outputs.file(output)
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", script.asFile.absolutePath)
}

val prepareEmbeddedBashHelper = tasks.register<Exec>("prepareEmbeddedBashHelper") {
    onlyIf { embeddedLettaCodeNativeEnabled.get() }
    notCompatibleWithConfigurationCache("Builds GNU bash for android-arm64 with the local NDK toolchain.")
    val script = project.layout.projectDirectory.file("../../scripts/build-android-bash.sh")
    val output = project.layout.projectDirectory.file("libs/embedded-bash/arm64-v8a/libbash.so")
    inputs.file(script)
    outputs.file(output)
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", script.asFile.absolutePath)
}

val prepareEmbeddedGitHelper = tasks.register<Exec>("prepareEmbeddedGitHelper") {
    onlyIf { embeddedLettaCodeNativeEnabled.get() }
    notCompatibleWithConfigurationCache("Builds git for android-arm64 with the local NDK toolchain.")
    val script = project.layout.projectDirectory.file("../../scripts/build-android-git.sh")
    val output = project.layout.projectDirectory.file("libs/embedded-git/arm64-v8a/libgit.so")
    inputs.file(script)
    outputs.file(output)
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", script.asFile.absolutePath)
}

val prepareEmbeddedNodeCli = tasks.register<Exec>("prepareEmbeddedNodeCli") {
    onlyIf { embeddedLettaCodeNativeEnabled.get() }
    notCompatibleWithConfigurationCache("Builds the Android node CLI wrapper against nodejs-mobile libnode.")
    dependsOn(prepareEmbeddedLettaCodeLibnode)
    val script = project.layout.projectDirectory.file("../../scripts/build-android-node-cli.sh")
    val source = project.layout.projectDirectory.file("src/main/cpp/embedded_node_cli.cpp")
    val output = project.layout.projectDirectory.file("libs/embedded-node-cli/arm64-v8a/libnodecli.so")
    inputs.file(script)
    inputs.file(source)
    inputs.dir(embeddedLettaCodeLibnodeDir)
    outputs.file(output)
    workingDir(rootProject.projectDir.parentFile)
    commandLine("bash", script.asFile.absolutePath)
}

val prepareEmbeddedLettaCodeAssets = tasks.register("prepareEmbeddedLettaCodeAssets") {
    onlyIf { embeddedLettaCodeAssetsEnabled.get() }
    notCompatibleWithConfigurationCache("Runs npm install and copies embedded LettaCode assets.")
    inputs.dir(project.layout.projectDirectory.dir("src/main/embedded-lettacode"))
    outputs.dir(embeddedLettaCodeAssetsDir)
    doLast {
        val npmWorkDir = layout.buildDirectory.dir("embedded-lettacode/npm").get().asFile
        val assetRoot = embeddedLettaCodeAssetsDir.get().asFile.resolve("letta-code/nodejs-project")
        delete(npmWorkDir)
        delete(assetRoot)
        npmWorkDir.mkdirs()
        assetRoot.mkdirs()
        val npmInstall = ProcessBuilder(
            npmCommand(),
            "install",
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
            "--prefix",
            npmWorkDir.absolutePath,
            "@letta-ai/letta-code@$embeddedLettaCodeVersion",
        )
            .directory(projectDir)
            .inheritIO()
            .start()
        check(npmInstall.waitFor() == 0) { "npm install for embedded LettaCode failed." }

        // nodejs-mobile's libnode is built WITHOUT ICU (--with-intl=none), so V8
        // rejects unicode property escapes (/\p{...}/u) with "Invalid property
        // name" SyntaxError at module load. Desktop Node parses these fine, which
        // is why letta.js "boots on Node 18" on a workstation but SIGABRTs inside
        // node::Start on device. Transpile property escapes to plain character
        // classes at asset-prep time (standard nodejs-mobile prior art via
        // regexpu/babel). See letta-mobile-7to5o.
        val babelInstall = ProcessBuilder(
            npmCommand(),
            "install",
            "--no-audit",
            "--no-fund",
            "--prefix",
            npmWorkDir.absolutePath,
            "@babel/core@7",
            "@babel/plugin-transform-unicode-property-regex@7",
            "regexpu-core@6",
        )
            .directory(projectDir)
            .inheritIO()
            .start()
        check(babelInstall.waitFor() == 0) { "npm install for babel transform failed." }

        // sharp ships no android-arm64 native binary, so module load aborts with
        // "Could not load the sharp module using the android-arm64 runtime".
        // sharp's own loader falls back to the wasm32 build when present —
        // install it (version must match the sharp dependency in letta-code).
        val sharpVersion = npmWorkDir.resolve("node_modules/sharp/package.json")
            .takeIf { it.isFile }
            ?.let { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
            ?.get("version") as? String
        if (sharpVersion != null) {
            val sharpWasmInstall = ProcessBuilder(
                npmCommand(),
                "install",
                "--no-audit",
                "--no-fund",
                "--ignore-scripts",
                // npm refuses cross-cpu installs (EBADPLATFORM) even with
                // --cpu=wasm32 on this npm version; --force is the documented
                // escape hatch for platform-package side-installs.
                "--force",
                "--prefix",
                npmWorkDir.absolutePath,
                "@img/sharp-wasm32@$sharpVersion",
            )
                .directory(projectDir)
                .inheritIO()
                .start()
            check(sharpWasmInstall.waitFor() == 0) { "npm install for @img/sharp-wasm32 failed." }
        }
        val transformScript = npmWorkDir.resolve("transform-unicode-property.mjs")
        transformScript.writeText(
            """
            import { transformAsync } from '@babel/core';
            import { readFileSync, writeFileSync } from 'fs';
            const file = process.argv[2];
            const src = readFileSync(file, 'utf8');
            const result = await transformAsync(src, {
              plugins: ['@babel/plugin-transform-unicode-property-regex'],
              compact: false,
              babelrc: false,
              configFile: false,
              parserOpts: { sourceType: 'unambiguous' },
            });
            writeFileSync(file, result.code);
            console.log('[embedded-lettacode] transpiled unicode property escapes in ' + file);
            """.trimIndent(),
        )
        val lettaJs = npmWorkDir.resolve("node_modules/@letta-ai/letta-code/letta.js")
        check(lettaJs.isFile) { "letta.js not found for unicode-property transform at ${lettaJs.absolutePath}" }
        val transform = ProcessBuilder(
            "node",
            "--max-old-space-size=8192",
            transformScript.absolutePath,
            lettaJs.absolutePath,
        )
            .directory(npmWorkDir)
            .inheritIO()
            .start()
        check(transform.waitFor() == 0) { "Unicode property escape transform for letta.js failed." }

        // letta-mobile-nojhc: make custom OpenAI-compatible (lmstudio/*) models
        // image-capable when they match a data-driven vision-model list. The
        // embedded letta.js customOpenAICompatibleModel() hardcodes
        // `input.modelId.includes("llava"|"vision"|"vl") ? ["text","image"] :
        // ["text"]`, so a vision-capable custom model like MiniMax-M3 gets
        // input:["text"] and the image is DROPPED before the provider request
        // (model.input.includes("image") gate). This patch routes the
        // capability through LETTA_CODE_VISION_MODEL_IDS (comma-separated,
        // case-insensitive SUBSTRINGS — so "minimax" matches "MiniMax-M3"),
        // mirroring the shim's LETTA_VISION_MODELS pattern so both surfaces
        // stay consistent. Adding a vision model = one list entry, no code edit.
        val visionFlagScript = npmWorkDir.resolve("patch-vision-model-input.mjs")
        visionFlagScript.writeText(
            """
            import { readFileSync, writeFileSync } from 'fs';
            const file = process.argv[2];
            let src = readFileSync(file, 'utf8');
            const token = 'input: input.modelId.includes("llava") || input.modelId.includes("vision") || input.modelId.includes("vl") ? ["text", "image"] : ["text"],';
            if (!src.includes(token)) {
              throw new Error('patch-vision-model-input: customOpenAICompatibleModel input token not found — letta.js shape changed');
            }
            const replacement =
              'input: ((() => { try { ' +
              'const h = String(input.modelId || "").toLowerCase(); ' +
              'const env = String(process.env.LETTA_CODE_VISION_MODEL_IDS || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean); ' +
              'if (env.some((p) => h.includes(p)) || /\\bvl\\b/.test(h) || h.includes("llava") || h.includes("vision")) return ["text", "image"]; ' +
              '} catch {} return ["text"]; })()),';
            src = src.replace(token, replacement);
            writeFileSync(file, src);
            console.log('[embedded-lettacode] patched customOpenAICompatibleModel for env-driven vision capability');
            """.trimIndent(),
        )
        val visionFlag = ProcessBuilder(
            "node",
            visionFlagScript.absolutePath,
            lettaJs.absolutePath,
        )
            .directory(npmWorkDir)
            .inheritIO()
            .start()
        check(visionFlag.waitFor() == 0) { "Vision-model-input transform for letta.js failed." }

        // letta-mobile-nojhc: letta.js's chat/completions image builder emits a
        // NON-STANDARD camelCase `imageUrl: "data:..."` part. OpenAI-compatible
        // providers (MiniMax, lmstudio, etc.) expect the standard
        // `image_url: { url: "data:..." }` and reject the camelCase form
        // ("invalid params, image_url is empty"), surfacing as the opaque
        // "999 (1000)" turn error. Rewrite both camelCase emit sites to the
        // standard shape so images actually reach the provider. PROVEN against
        // the live :8082 proxy: standard shape → 200 + description; camelCase →
        // bad_request "image_url is empty".
        val imageUrlScript = npmWorkDir.resolve("patch-image-url-shape.mjs")
        imageUrlScript.writeText(
            """
            import { readFileSync, writeFileSync } from 'fs';
            const file = process.argv[2];
            let src = readFileSync(file, 'utf8');
            // Whitespace-agnostic: convert every chat/completions camelCase
            // image_url part into the OpenAI-standard
            // type:"image_url", image_url:{ url: <data-url-template> }.
            // Matches the data-URL template body without pinning indentation,
            // so both emit sites convert regardless of minifier whitespace.
            const re = /type:\s*"image_url",\s*imageUrl:\s*(`data:[^`]*`)/g;
            let changed = 0;
            src = src.replace(re, (_m, urlExpr) => {
              changed++;
              return 'type: "image_url", image_url: { url: ' + urlExpr + ' }';
            });
            if (changed === 0) {
              throw new Error('patch-image-url-shape: no camelCase imageUrl tokens found — letta.js shape changed');
            }
            writeFileSync(file, src);
            console.log('[embedded-lettacode] rewrote ' + changed + ' camelCase imageUrl -> standard image_url{url}');
            """.trimIndent(),
        )
        val imageUrlFix = ProcessBuilder("node", imageUrlScript.absolutePath, lettaJs.absolutePath)
            .directory(npmWorkDir).inheritIO().start()
        check(imageUrlFix.waitFor() == 0) { "image_url-shape transform for letta.js failed." }

        // letta-mobile-nojhc: neutralize letta.js's image-resize path on device.
        // resizeImageIfNeeded() calls getImageDimensions() (sharp) + the magick
        // CLI — neither loads on nodejs-mobile, so an image send would die with
        // "999 (1000)" in failureMode:strict. The composer already downsamples
        // client-side, so the resize is redundant — rewrite it to a passthrough.
        val sharpBypassScript = npmWorkDir.resolve("bypass-image-resize.mjs")
        sharpBypassScript.writeText(
            """
            import { readFileSync, writeFileSync } from 'fs';
            const file = process.argv[2];
            let src = readFileSync(file, 'utf8');
            const marker = 'async function resizeImageIfNeeded(buffer, inputMediaType) {';
            const idx = src.indexOf(marker);
            if (idx === -1) {
              throw new Error('bypass-image-resize: resizeImageIfNeeded marker not found — letta.js shape changed');
            }
            let i = idx + marker.length;
            let depth = 1;
            while (i < src.length && depth > 0) {
              const ch = src[i];
              if (ch === '{') depth++;
              else if (ch === '}') depth--;
              i++;
            }
            if (depth !== 0) throw new Error('bypass-image-resize: unbalanced braces');
            const replacement = marker +
              '\n  // letta-mobile-nojhc: device passthrough, no sharp/magick.\n' +
              '  return { data: buffer.toString("base64"), mediaType: inputMediaType, width: 0, height: 0, resized: false };\n}';
            src = src.slice(0, idx) + replacement + src.slice(i);
            writeFileSync(file, src);
            console.log('[embedded-lettacode] bypassed resizeImageIfNeeded (sharp/magick) for on-device images');
            """.trimIndent(),
        )
        val sharpBypass = ProcessBuilder(
            "node",
            sharpBypassScript.absolutePath,
            lettaJs.absolutePath,
        )
            .directory(npmWorkDir)
            .inheritIO()
            .start()
        check(sharpBypass.waitFor() == 0) { "Image-resize bypass transform for letta.js failed." }

        // letta-mobile-st78v: expose letta.js's in-process agent/conversation
        // switch capability to the embedded headless stream-json control path.
        // Android keeps the single node::Start process alive and sends this over
        // stdin instead of stop()+start() re-entering node::Start.
        val switchSessionScript = npmWorkDir.resolve("patch-switch-session-control.mjs")
        switchSessionScript.writeText(
            """
            import { readFileSync, writeFileSync } from 'fs';
            const file = process.argv[2];
            let src = readFileSync(file, 'utf8');
            const marker = 'letta-mobile-st78v-switch-session-control';
            if (src.includes(marker)) {
              console.warn('[embedded-lettacode] switch_session control already patched');
              process.exit(0);
            }
            for (const required of [
              'function setCurrentAgentId(agentId)',
              'function setConversationId(conversationId)',
              'if (message.type === "control_request")',
              'subtype === "interrupt"'
            ]) {
              if (!src.includes(required)) {
                throw new Error('patch-switch-session-control: required token not found — letta.js shape changed: ' + required);
              }
            }
            const token = `      } else if (subtype === "interrupt") {
        if (currentAbortController !== null) {
          currentAbortController.abort();
          currentAbortController = null;
        }
        const interruptResponse = {
          type: "control_response",
          response: {
            subtype: "success",
            request_id: requestId ?? ""
          },
          session_id: sessionId,
          uuid: randomUUID19()
        };
        writeWireMessage(interruptResponse);
      } else if (subtype === "register_external_tools") {`;
            if (!src.includes(token)) {
              throw new Error('patch-switch-session-control: interrupt control branch token not found — letta.js shape changed');
            }
            const replacement = `      } else if (subtype === "interrupt") {
        if (currentAbortController !== null) {
          currentAbortController.abort();
          currentAbortController = null;
        }
        const interruptResponse = {
          type: "control_response",
          response: {
            subtype: "success",
            request_id: requestId ?? ""
          },
          session_id: sessionId,
          uuid: randomUUID19()
        };
        writeWireMessage(interruptResponse);
      } else if (subtype === "switch_session") {
        // letta-mobile-st78v-switch-session-control
        const switchAgentId = message.request?.agent_id ?? message.agent_id;
        const switchConversationId = message.request?.conversation_id ?? message.conversation_id;
        if (typeof switchAgentId !== "string" || switchAgentId.length === 0 || typeof switchConversationId !== "string" || switchConversationId.length === 0) {
          const switchErrorResponse = {
            type: "control_response",
            response: {
              subtype: "error",
              request_id: requestId ?? "",
              error: "switch_session requires agent_id and conversation_id"
            },
            session_id: sessionId,
            uuid: randomUUID19()
          };
          writeWireMessage(switchErrorResponse);
          continue;
        }
        if (currentAbortController !== null) {
          currentAbortController.abort();
          currentAbortController = null;
        }
        agent2 = await backend4.retrieveAgent(switchAgentId, {
          include: ["agent.secrets", "agent.tools", "agent.tags"]
        });
        conversationId = switchConversationId;
        telemetry.setCurrentAgentId(agent2.id);
        setCurrentAgentId(agent2.id);
        setConversationId(conversationId);
        setAgentContext(agent2.id, skillsDirectory, resolvedSkillSources);
        availableTools = agent2.tools?.map(t2 => t2.name).filter(n => !!n) || [];
        try {
          const switchedToolContext = await prepareHeadlessToolExecutionContext({
            agentId: agent2.id,
            conversationId,
            cachedAgent: agent2
          });
          availableTools = switchedToolContext.availableTools;
          cachedAgent = switchedToolContext.cachedAgent;
          preparedEffectiveModel = switchedToolContext.preparedEffectiveModel;
        } catch (switchContextError) {
          if (isDebugEnabled()) {
            console.warn("[embedded-lettacode] switch_session tool-context refresh failed", switchContextError);
          }
        }
        if (shouldPersistSessionState()) {
          settingsManager.persistSession(agent2.id, conversationId);
        }
        msgQueueRuntime.clear("switch_session");
        const switchResponse = {
          type: "control_response",
          response: {
            subtype: "success",
            request_id: requestId ?? "",
            response: {
              agent_id: agent2.id,
              conversation_id: conversationId,
              tools: availableTools
            }
          },
          session_id: sessionId,
          uuid: randomUUID19()
        };
        writeWireMessage(switchResponse);
      } else if (subtype === "register_external_tools") {`;
            src = src.replace(token, replacement);
            if (!src.includes(marker)) {
              throw new Error('patch-switch-session-control: replacement did not land');
            }
            writeFileSync(file, src);
            console.log('[embedded-lettacode] patched headless stream-json switch_session control_request');
            """.trimIndent(),
        )
        val switchSessionPatch = ProcessBuilder("node", switchSessionScript.absolutePath, lettaJs.absolutePath)
            .directory(npmWorkDir).inheritIO().start()
        check(switchSessionPatch.waitFor() == 0) { "switch_session control transform for letta.js failed." }

        // origin/main #478: keep tool-call groups intact during trimming.
        val toolGroupPatch = ProcessBuilder(
            "node",
            project.rootDir.parentFile.resolve("scripts/patch-embedded-letta-code-tool-groups.cjs").absolutePath,
            lettaJs.absolutePath,
        )
            .directory(npmWorkDir)
            .inheritIO()
            .start()
        check(toolGroupPatch.waitFor() == 0) { "Tool-call group trimming patch for letta.js failed." }

        // letta-mobile-84a59: @letta-ai/letta-code 0.26.1 has generic system-reminders
        // but not the shim's runtime-introspection strings ("Context utilization",
        // "Serving model", "Session role", "Model changed"). Ship the canonical shim
        // module and patch the embedded stream-json turn boundary to call it.
        copy {
            from(project.layout.projectDirectory.dir("src/main/embedded-lettacode"))
            into(assetRoot)
        }
        val introspectionPatchNeedle =
            "        const enrichedContent = prependReminderPartsToContent(userContent, sharedReminderParts);\n" +
                "        let currentInput = [{"
        val introspectionPatchReplacement =
            "        let enrichedContent = prependReminderPartsToContent(userContent, sharedReminderParts);\n" +
                "        if (typeof globalThis.__lettaMobileBuildRuntimeIntrospectionContent === \"function\") {\n" +
                "          enrichedContent = await globalThis.__lettaMobileBuildRuntimeIntrospectionContent(agent2.id, conversationId, enrichedContent);\n" +
                "        }\n" +
                "        let currentInput = [{"
        val originalLettaJsForIntrospection = lettaJs.readText()
        val patchedLettaJsForIntrospection = originalLettaJsForIntrospection.replace(
            introspectionPatchNeedle,
            introspectionPatchReplacement,
        )
        check(patchedLettaJsForIntrospection != originalLettaJsForIntrospection) {
            "Failed to patch letta.js runtime-introspection turn boundary."
        }
        lettaJs.writeText(patchedLettaJsForIntrospection)
        listOf(
            assetRoot.resolve("letta-mobile-runtime-introspection/store.js"),
            assetRoot.resolve("letta-mobile-runtime-introspection/runtime-introspection.js"),
            assetRoot.resolve("embedded-runtime-introspection-preload.cjs"),
            lettaJs,
        ).forEach { file ->
            val checkProcess = ProcessBuilder("node", "--check", file.absolutePath)
                .directory(npmWorkDir)
                .inheritIO()
                .start()
            check(checkProcess.waitFor() == 0) { "${file.name} failed node --check." }
        }

        // Ship npm so the on-device node can install packages. npm is pure JS
        // (npm-cli.js) and runs on the embedded node 18 runtime. Pin the last
        // npm that officially supports Node 18 (10.9.x). Install it into a
        // SEPARATE staging dir (installing npm into the same tree it's part of
        // confuses dedup), then copy node_modules/npm into the embedded tree.
        // An `npm` PATH wrapper (created at runtime) runs node npm-cli.js. See
        // letta-mobile-iq24j.
        val npmStageDir = layout.buildDirectory.dir("embedded-lettacode/npm-stage").get().asFile
        delete(npmStageDir)
        npmStageDir.mkdirs()
        val npmForDeviceInstall = ProcessBuilder(
            npmCommand(),
            "install",
            "--no-audit",
            "--no-fund",
            "--ignore-scripts",
            "--prefix",
            npmStageDir.absolutePath,
            "npm@10.9.2",
        )
            .directory(npmStageDir)
            .inheritIO()
            .start()
        check(npmForDeviceInstall.waitFor() == 0) { "npm install for on-device npm failed." }
        val stagedNpm = npmStageDir.resolve("node_modules/npm")
        check(stagedNpm.resolve("bin/npm-cli.js").isFile) { "staged npm-cli.js missing at ${stagedNpm.absolutePath}" }
        copy {
            from(stagedNpm)
            into(npmWorkDir.resolve("node_modules/npm"))
        }

        copy {
            from(npmWorkDir.resolve("node_modules"))
            into(assetRoot.resolve("node_modules"))
            // babel is a build-time tool only; don't ship it on device.
            // regexpu-core (+ regenerate/regjs*/unicode-* data) IS shipped: the
            // runtime RegExp polyfill below needs it for dynamically constructed
            // patterns that the static babel pass cannot reach.
            exclude("@babel/**", ".bin/**", "@jridgewell/**", "browserslist/**", "caniuse-lite/**", "electron-to-chromium/**")
        }
        // Runtime guard for ICU-less V8: letta.js also builds regexes at RUNTIME
        // (e.g. the oniguruma-to-js highlighter calls new RegExp with \p{...}
        // patterns), which static transpilation cannot reach. Preload a RegExp
        // wrapper (node --require) that retries failed compilations through
        // regexpu-core with property escapes transformed.
        assetRoot.resolve("regexp-polyfill.cjs").writeText(
            """
            'use strict';
            // nodejs-mobile libnode has no ICU: /\p{...}/u throws "Invalid
            // property name". Retry failed patterns through regexpu-core.
            const rewritePattern = require('regexpu-core');
            const NativeRegExp = RegExp;
            function rewriteOptions(flags) {
              return {
                unicodePropertyEscapes: 'transform',
                unicodeSetsFlag: flags.includes('v') ? 'transform' : false,
              };
            }
            function PatchedRegExp(pattern, flags) {
              try {
                return flags === undefined
                  ? new NativeRegExp(pattern)
                  : new NativeRegExp(pattern, flags);
              } catch (originalError) {
                if (pattern instanceof NativeRegExp && flags === undefined) throw originalError;
                try {
                  const sourceFlags = flags !== undefined
                    ? String(flags)
                    : (pattern instanceof NativeRegExp ? pattern.flags : '');
                  const sourcePattern = pattern instanceof NativeRegExp ? pattern.source : String(pattern);
                  const rewritten = rewritePattern(sourcePattern, sourceFlags, rewriteOptions(sourceFlags));
                  const outFlags = sourceFlags.replace('v', 'u');
                  return new NativeRegExp(rewritten, outFlags);
                } catch (_) {
                  throw originalError;
                }
              }
            }
            PatchedRegExp.prototype = NativeRegExp.prototype;
            Object.setPrototypeOf(PatchedRegExp, NativeRegExp);
            globalThis.RegExp = PatchedRegExp;

            // ICU-less node also lacks the entire Intl namespace. Provide the
            // minimal surface letta.js touches. Segmenter: code-point graphemes
            // (good enough for width/wrapping on device). Formatters: passthrough.
            if (typeof globalThis.Intl === 'undefined') {
              class Segmenter {
                constructor(_locale, options) { this.granularity = (options && options.granularity) || 'grapheme'; }
                segment(input) {
                  const str = String(input);
                  const segments = [];
                  if (this.granularity === 'word') {
                    const re = /\S+|\s+/g; let m;
                    while ((m = re.exec(str)) !== null) {
                      segments.push({ segment: m[0], index: m.index, input: str, isWordLike: /\S/.test(m[0]) });
                    }
                  } else {
                    let index = 0;
                    for (const ch of str) { segments.push({ segment: ch, index, input: str }); index += ch.length; }
                  }
                  segments[Symbol.iterator] = Array.prototype[Symbol.iterator];
                  segments.containing = (i) => segments.find(s => s.index <= i && i < s.index + s.segment.length);
                  return segments;
                }
              }
              class NumberFormat {
                constructor() {}
                format(n) { return String(n); }
                formatToParts(n) { return [{ type: 'integer', value: String(n) }]; }
                resolvedOptions() { return { locale: 'en-US' }; }
              }
              class DateTimeFormat {
                constructor() {}
                format(d) { return new Date(d ?? Date.now()).toISOString(); }
                formatToParts(d) { return [{ type: 'literal', value: this.format(d) }]; }
                resolvedOptions() { return { locale: 'en-US', timeZone: 'UTC' }; }
              }
              class Collator {
                constructor() {}
                compare(a, b) { return a < b ? -1 : a > b ? 1 : 0; }
                resolvedOptions() { return { locale: 'en-US' }; }
              }
              class PluralRules {
                constructor() {}
                select(n) { return n === 1 ? 'one' : 'other'; }
                resolvedOptions() { return { locale: 'en-US' }; }
              }
              class RelativeTimeFormat {
                constructor() {}
                format(value, unit) { return value + ' ' + unit + (Math.abs(value) === 1 ? '' : 's'); }
                formatToParts(value, unit) { return [{ type: 'literal', value: this.format(value, unit) }]; }
                resolvedOptions() { return { locale: 'en-US' }; }
              }
              class ListFormat {
                constructor() {}
                format(list) { return Array.from(list).join(', '); }
                resolvedOptions() { return { locale: 'en-US' }; }
              }
              const supported = (cls) => { cls.supportedLocalesOf = () => ['en-US']; return cls; };
              globalThis.Intl = {
                Segmenter: supported(Segmenter),
                NumberFormat: supported(NumberFormat),
                DateTimeFormat: supported(DateTimeFormat),
                Collator: supported(Collator),
                PluralRules: supported(PluralRules),
                RelativeTimeFormat: supported(RelativeTimeFormat),
                ListFormat: supported(ListFormat),
                getCanonicalLocales: (l) => (Array.isArray(l) ? l : l ? [l] : []),
              };
            }
            """.trimIndent(),
        )
        val androidNetworkPolyfill = assetRoot.resolve("android-network-polyfill.cjs")
        androidNetworkPolyfill.writeText(
            """
            'use strict';
            const bridgeBaseUrl = process.env.LETTA_ANDROID_NETWORK_BRIDGE_URL;
            if (bridgeBaseUrl) {
              const http = require('http');
              const dns = require('dns');
              const { Buffer } = require('buffer');
              const { Readable } = require('stream');
              function postJson(path, payload) {
                return new Promise((resolve, reject) => {
                  const body = JSON.stringify(payload || {});
                  const url = new URL(path, bridgeBaseUrl);
                  const req = http.request({
                    hostname: url.hostname,
                    port: url.port,
                    path: url.pathname,
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/json',
                      'Content-Length': Buffer.byteLength(body),
                    },
                  }, (res) => {
                    const chunks = [];
                    res.on('data', (chunk) => chunks.push(chunk));
                    res.on('end', () => {
                      const text = Buffer.concat(chunks).toString('utf8');
                      let parsed;
                      try { parsed = text ? JSON.parse(text) : {}; } catch (error) { reject(error); return; }
                      if (res.statusCode >= 400) {
                        const message = parsed && parsed.error && parsed.error.message ? parsed.error.message : 'Android bridge HTTP ' + res.statusCode;
                        reject(new Error(message));
                      } else {
                        resolve(parsed);
                      }
                    });
                  });
                  req.on('error', reject);
                  req.write(body);
                  req.end();
                });
              }
              const nativeFetch = globalThis.fetch;
              const forceAndroidFetch = process.env.LETTA_ANDROID_NETWORK_BRIDGE_FORCE_FETCH === '1';
              if (forceAndroidFetch || typeof nativeFetch !== 'function') {
                globalThis.fetch = async function androidBridgeFetch(input, init = {}) {
                  const url = typeof input === 'string' ? input : (input && input.url) ? input.url : String(input);
                  const method = init.method || (input && input.method) || 'GET';
                  const headers = {};
                  const sourceHeaders = init.headers || (input && input.headers);
                  if (sourceHeaders) {
                    if (typeof sourceHeaders.forEach === 'function') {
                      sourceHeaders.forEach((value, key) => { headers[key] = value; });
                    } else if (Array.isArray(sourceHeaders)) {
                      for (const [key, value] of sourceHeaders) headers[key] = value;
                    } else {
                      Object.assign(headers, sourceHeaders);
                    }
                  }
                  const requestBody = init.body == null ? undefined : String(init.body);
                  const bridged = await postJson('/fetch', { url, method, headers, body: requestBody });
                  const responseBody = Buffer.from(bridged.bodyBase64 || '', 'base64');
                  const rawHeaders = bridged.headers || {};
                  const headerEntries = Object.entries(rawHeaders).map(([key, value]) => [String(key).toLowerCase(), String(value)]);
                  const responseHeaders = {
                    get(name) {
                      const key = String(name).toLowerCase();
                      const found = headerEntries.find(([entryKey]) => entryKey === key);
                      return found ? found[1] : null;
                    },
                    has(name) { return this.get(name) !== null; },
                    forEach(callback, thisArg) { headerEntries.forEach(([key, value]) => callback.call(thisArg, value, key, responseHeaders)); },
                    entries() { return headerEntries[Symbol.iterator](); },
                    keys() { return headerEntries.map(([key]) => key)[Symbol.iterator](); },
                    values() { return headerEntries.map(([, value]) => value)[Symbol.iterator](); },
                    [Symbol.iterator]() { return this.entries(); },
                  };
                  return {
                    ok: bridged.status >= 200 && bridged.status < 300,
                    status: bridged.status,
                    statusText: bridged.statusText || '',
                    url,
                    headers: responseHeaders,
                    body: Readable.from([responseBody]),
                    arrayBuffer: async () => responseBody.buffer.slice(responseBody.byteOffset, responseBody.byteOffset + responseBody.byteLength),
                    text: async () => responseBody.toString('utf8'),
                    json: async () => JSON.parse(responseBody.toString('utf8')),
                  };
                };
                globalThis.fetch.__androidBridgeOriginal = nativeFetch;
              }
              const originalLookup = dns.lookup;
              // IP literals need no DNS — resolving them through the bridge adds
              // an HTTP round-trip per request (e.g. every call to a LAN provider
              // like 192.168.50.90). Short-circuit them locally.
              const IPV4_RE = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}${'$'}/;
              function bridgeLookup(hostname, options, callback) {
                if (typeof options === 'function') { callback = options; options = {}; }
                const wantsAll = options && options.all === true;
                if (typeof hostname === 'string' && (IPV4_RE.test(hostname) || hostname.indexOf(':') !== -1)) {
                  const family = hostname.indexOf(':') !== -1 ? 6 : 4;
                  if (wantsAll) { callback(null, [{ address: hostname, family }]); }
                  else { callback(null, hostname, family); }
                  return;
                }
                postJson('/dns/lookup', { hostname }).then((result) => {
                  const addresses = result.addresses || [];
                  if (wantsAll) {
                    callback(null, addresses.map((item) => ({ address: item.address, family: item.family })));
                  } else {
                    const first = addresses[0];
                    if (!first) callback(new Error('No DNS records for ' + hostname));
                    else callback(null, first.address, first.family);
                  }
                }, callback);
              }
              dns.lookup = bridgeLookup;
              const dnsPromises = dns.promises;
              if (dnsPromises) {
                dnsPromises.lookup = async (hostname, options = {}) => new Promise((resolve, reject) => {
                  bridgeLookup(hostname, options, (error, address, family) => {
                    if (error) reject(error);
                    else resolve(options && options.all ? address : { address, family });
                  });
                });
              }
              dns.lookup.__androidBridgeOriginal = originalLookup;
            }
            """.trimIndent(),
        )
        val shimSyntaxCheck = ProcessBuilder("node", "--check", androidNetworkPolyfill.absolutePath)
            .directory(npmWorkDir)
            .inheritIO()
            .start()
        check(shimSyntaxCheck.waitFor() == 0) { "android-network-polyfill.cjs failed node --check." }
        assetRoot.resolve("package.json").writeText(
            """
            {
              "name": "letta-mobile-embedded-lettacode",
              "private": true,
              "type": "module",
              "dependencies": {
                "@letta-ai/letta-code": "$embeddedLettaCodeVersion"
              }
            }
            """.trimIndent(),
        )
    }
}

val testEmbeddedLettaCodeToolGroupPatch = tasks.register<Exec>("testEmbeddedLettaCodeToolGroupPatch") {
    description = "Runs regression tests for embedded LettaCode tool-call group trimming."
    group = "verification"
    commandLine(
        "node",
        project.rootDir.parentFile.resolve("scripts/patch-embedded-letta-code-tool-groups.test.cjs").absolutePath,
    )
}

tasks.named("check") {
    dependsOn(testEmbeddedLettaCodeToolGroupPatch)
}

android {
    namespace = "com.letta.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.letta.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep Play as the conservative dependency target for any future
        // library module that does not declare the distribution dimension.
        missingDimensionStrategy("distribution", "play")

        // Sentry config is read at runtime by [SentryInitializer] from
        // generated string resources. See letta-mobile-o7ob.7 — manifest
        // meta-data can't express a float like traces.sample-rate.
        val sentryDsn = localProps.getProperty("sentry.dsn", "")
        val sentryEnv = localProps.getProperty("sentry.environment", "development")
        resValue("string", "sentry_dsn", sentryDsn)
        resValue("string", "sentry_env", sentryEnv)
        manifestPlaceholders["SENTRY_DSN"] = sentryDsn
        manifestPlaceholders["SENTRY_ENV"] = sentryEnv
        buildConfigField("boolean", "EMBEDDED_LETTACODE_NATIVE_ENABLED", embeddedLettaCodeNativeEnabled.get().toString())
        buildConfigField("boolean", "EMBEDDED_LETTACODE_ASSETS_ENABLED", embeddedLettaCodeAssetsEnabled.get().toString())
        buildConfigField("String", "EMBEDDED_LETTACODE_VERSION", "\"$embeddedLettaCodeVersion-r$embeddedLettaCodeAssetRevision\"")
        buildConfigField("String", "EMBEDDED_LETTACODE_INTEGRITY", "\"$embeddedLettaCodeIntegrity\"")

        if (embeddedLettaCodeNativeEnabled.get()) {
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
            externalNativeBuild {
                cmake {
                    arguments += "-DLETTACODE_LIBNODE_DIR=${embeddedLettaCodeLibnodeDir.get().asFile.absolutePath.replace("\\", "/")}" 
                    arguments += "-DANDROID_STL=c++_shared"
                }
            }
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"play\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "false")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "false")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "false")
        }
        create("sideload") {
            dimension = "distribution"
            versionNameSuffix = "-sideload"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"sideload\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "true")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "true")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "false")
        }
        create("root") {
            dimension = "distribution"
            versionNameSuffix = "-root"
            buildConfigField("String", "SYSTEM_ACCESS_FLAVOR", "\"root\"")
            buildConfigField("boolean", "ENABLE_LOCAL_SHELL", "true")
            buildConfigField("boolean", "ENABLE_SHIZUKU", "true")
            buildConfigField("boolean", "ENABLE_ROOT_TOOLS", "true")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                val props = Properties().apply { load(keystorePropsFile.inputStream()) }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                val envStoreFile = providers.environmentVariable("SIGNING_STORE_FILE").orNull?.takeIf { it.isNotEmpty() }
                storeFile = file(envStoreFile ?: "letta-release.jks")
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull ?: ""
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull ?: ""
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull ?: ""
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
        }
        // `benchmark` mirrors release (minified + shrunk) but is
        // debuggable+profileable so Macrobenchmark can hook into it.
        // Signed with the debug cert so `installRelease` keystores aren't
        // needed on dev machines running benchmarks.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // `profileable` is what macrobench uses to read perf counters
            // without pulling the debugger in.
            isDebuggable = false
            isProfileable = true
            // Keep the app id suffix distinct so dev builds can coexist.
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Memory caps — see core/build.gradle.kts for rationale.
                // The app suite is the heaviest Robolectric/Hilt test shard;
                // fork more often so long CI runs do not carry retained class
                // loader state into late suites such as ProjectHomeViewModelTest.
                it.maxHeapSize = "3072m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=512m")
                it.setForkEvery(20L)
                it.filter {
                    excludeTestsMatching("*ScreenshotTest")
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
        if (embeddedLettaCodeNativeEnabled.get()) {
            // libgit.so is exec()'d as a program (memfs git), which requires
            // a real file in nativeLibraryDir — loading straight from the APK
            // (the non-legacy default) only supports dlopen.
            jniLibs.useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("test") {
            kotlin.directories += "src/test/java"
        }
        if (embeddedLettaCodeAssetsEnabled.get()) {
            getByName("main") {
                assets.directories.add(embeddedLettaCodeAssetsDir.get().asFile.absolutePath)
            }
        }
        if (embeddedLettaCodeNativeEnabled.get()) {
            getByName("main") {
                jniLibs.directories.add(embeddedLettaCodeLibnodeDir.get().asFile.resolve("bin").absolutePath)
                // git for android-arm64, shipped as libgit.so so it lands in
                // the executable nativeLibraryDir (filesDir is noexec). Built
                // by scripts/build-android-git.sh (gitignored); when absent,
                // the runtime keeps local memfs disabled instead of failing.
                jniLibs.directories.add(file("libs/embedded-git").absolutePath)
                // GNU bash for android-arm64 (libbash.so), same trick. Built
                // by scripts/build-android-bash.sh (gitignored); when absent,
                // the runtime aliases /system/bin/sh under the name instead.
                jniLibs.directories.add(file("libs/embedded-bash").absolutePath)
                // curl-compatible helper (libcurl.so) that delegates HTTP(S)
                // through AndroidNetworkBridge. Built by scripts/build-android-curl.sh.
                jniLibs.directories.add(file("libs/embedded-curl").absolutePath)
                // node CLI wrapper (libnodecli.so) linked against nodejs-mobile
                // libnode.so so local tools can run `node -e` scripts.
                jniLibs.directories.add(file("libs/embedded-node-cli").absolutePath)
            }
        }
        // The `benchmark` buildType (macrobenchmark target) uses the same
        // no-op DebugPerformanceMonitor as release so benchmarks measure
        // the production code path, not the debug instrumentation stack.
        getByName("benchmark") {
            kotlin.directories += "src/release/java"
        }
    }

    if (embeddedLettaCodeNativeEnabled.get()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
}

val verifyEmbeddedLettaCodeSwitchSessionControl = tasks.register("verifyEmbeddedLettaCodeSwitchSessionControl") {
    dependsOn(prepareEmbeddedLettaCodeAssets)
    inputs.dir(embeddedLettaCodeAssetsDir)
    doLast {
        val lettaJs = embeddedLettaCodeAssetsDir.get().asFile
            .resolve("letta-code/nodejs-project/node_modules/@letta-ai/letta-code/letta.js")
        check(lettaJs.isFile) { "Prepared embedded letta.js not found at ${lettaJs.absolutePath}" }
        val src = lettaJs.readText()
        check(src.contains("letta-mobile-st78v-switch-session-control")) {
            "Prepared embedded letta.js is missing switch_session control handler marker."
        }
        check(src.contains("subtype === \"switch_session\"")) {
            "Prepared embedded letta.js is missing switch_session subtype branch."
        }
    }
}

if (embeddedLettaCodeAssetsEnabled.get()) {
    tasks.configureEach {
        if (name.startsWith("merge") && name.endsWith("Assets")) {
            dependsOn(prepareEmbeddedLettaCodeAssets)
        }
        // Release builds run lint-vital, whose model/analyze tasks read the
        // merged-assets output (which now includes the embedded LettaCode
        // assets). Gradle's strict task-dependency validation fails the build
        // ("uses this output ... without declaring an explicit or implicit
        // dependency") unless the lint-vital tasks also depend on the asset
        // prep. Wire them so :app:assemble*Release works with
        // -PembedLettaCodeAssets=true (and so the CI release path is clean).
        if (
            (name.startsWith("lintVital") || name.startsWith("generate") && name.contains("LintVital")) &&
            name.contains("Release")
        ) {
            dependsOn(prepareEmbeddedLettaCodeAssets)
        }
    }
}

if (embeddedLettaCodeNativeEnabled.get()) {
    tasks.configureEach {
        if (
            name.contains("CMake") ||
            (name.startsWith("merge") && name.endsWith("NativeLibs")) ||
            (name.startsWith("merge") && name.endsWith("JniLibFolders"))
        ) {
            dependsOn(prepareEmbeddedLettaCodeLibnode)
            dependsOn(prepareEmbeddedBashHelper)
            dependsOn(prepareEmbeddedGitHelper)
            dependsOn(prepareEmbeddedCurlHelper)
            dependsOn(prepareEmbeddedNodeCli)
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}

// Consumes the baseline profile produced by the :baselineprofile
// module. The plugin automatically wires generated profiles into
// release APKs so install-time AOT compilation warms the hot path.
// See letta-mobile-o7ob.2.1.
baselineProfile {
    // Don't regenerate on every release build — generation requires a
    // connected device. CI runs `:app:generateBenchmarkBaselineProfile`
    // in a dedicated job.
    automaticGenerationDuringBuild = false
    // Check the generated profile into source control so local release
    // builds (without a device) still ship with a profile.
    saveInSrc = true
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-Xcontext-parameters",
        )
    }
}

dependencies {
    implementation(project(":core:data"))
    testImplementation(project(":core:testutil"))
    implementation(project(":designsystem"))
    implementation(project(":feature-chat"))
    implementation(project(":feature-editagent"))
    implementation("io.github.vinceglb:filekit-core:0.14.1")
    implementation("io.github.vinceglb:filekit-dialogs-compose:0.14.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha17")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("app.cash.molecule:molecule-runtime:2.2.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.metrics:metrics-performance:1.0.0")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:3.0-alpha-8")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0-beta01")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("io.ktor:ktor-client-logging:3.5.0")
    implementation("io.ktor:ktor-client-auth:3.5.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // DataStore + Encrypted SharedPreferences
    implementation("androidx.datastore:datastore-preferences:1.3.0-alpha09")
    implementation("androidx.security:security-crypto:1.1.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Coil (image loading)
    implementation("io.coil-kt.coil3:coil-compose:3.5.0-beta01")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.5.0-beta01")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // On-device LLM inference for explicitly constructed .litertlm engines.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    // Drag-to-reorder for Compose
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Immutable collections for Compose stability
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0-beta01")

    // Fuzzy search
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Vico charts
    implementation("com.patrykandpatrick.vico:compose-m3:3.2.0-next.5")

    // Timeline visualization
    implementation("io.github.pushpalroy:jetlime:4.3.0")

    // Paging 3
    implementation("androidx.paging:paging-runtime-ktx:3.5.0")
    implementation("androidx.paging:paging-compose:3.5.0")

    // Background sync
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Sentry error tracking — initialized programmatically via
    // androidx.startup in SentryInitializer. See letta-mobile-o7ob.7.
    implementation("io.sentry:sentry-android:8.42.0")
    implementation("androidx.startup:startup-runtime:1.2.0")

    // Baseline Profile installer — reads the bundled profile and warms
    // AOT compilation on first launch. See letta-mobile-o7ob.2.1 and
    // letta-mobile-o7ob.2.4.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.0")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.11")
    testImplementation("io.kotest:kotest-assertions-core:6.1.11")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.63.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.63.0")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")

    // Hilt testing
    testImplementation("com.google.dagger:hilt-android-testing:2.59.2")
    kspTest("com.google.dagger:hilt-compiler:2.59.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Test tier tasks use Root debug as the full-featured local default.
// assembleDebug still builds every distribution debug APK, but these filtered
// test aliases should exercise the artifact with all compile-time feature gates
// enabled instead of the Play-policy-constrained variant.
tasks.register<Test>("testUnit") {
    description = "Runs unit-tier tests (pure logic, <50ms per test)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("unit")
    }

    systemProperty("kotest.tags.include", "unit")
}

tasks.register<Test>("testIntegration") {
    description = "Runs integration-tier tests (Robolectric, Compose, ViewModels)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("kotest.tags.include", "integration")
}

tasks.register<Test>("testScreenshot") {
    description = "Runs screenshot-tier tests (Roborazzi visual regression)"
    group = "verification"

    val testTask = tasks.named("testRootDebugUnitTest", Test::class).get()
    mustRunAfter(testTask)
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    maxHeapSize = testTask.maxHeapSize
    jvmArgs(testTask.jvmArgs.orEmpty())
    setForkEvery(1L)

    useJUnitPlatform {
        includeEngines("junit-vintage")
    }
    filter {
        includeTestsMatching("*ScreenshotTest")
        isFailOnNoMatchingTests = false
    }
}
