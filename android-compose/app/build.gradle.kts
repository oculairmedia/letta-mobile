import java.util.Properties

plugins {
    id("com.android.application")
    // Dev-only on-device letta-code runtime: npm/libnode/NDK prep tasks, letta.js patches
    // (app/embedded-runtime/), EMBEDDED_LETTACODE_* BuildConfig. See build-logic/.../embedded/.
    id("com.letta.mobile.embedded-runtime")
    id("io.github.takahirom.roborazzi")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("io.gitlab.arturbosch.detekt")
    id("io.sentry.android.gradle")
    id("androidx.baselineprofile")
    id("com.mikepenz.aboutlibraries.plugin.android")
    id("org.jetbrains.kotlinx.kover") // version inherited from root
}

// Kotzilla observability — debug builds only, gated via Gradle property.
// Applied conditionally after the plugins{} block so the SDK is not present
// in release APKs at all (no ContentProvider, no startup overhead).
//
// Enable with: ./gradlew :app:assembleRootDebug -Pkotzilla=true
// Disable with: omit the property (default — release builds stay clean).
val kotzillaEnabled = providers.gradleProperty("kotzilla").orNull == "true"
if (kotzillaEnabled) {
    apply(plugin = "io.kotzilla.kotzilla-plugin")
}

kover {
    currentProject {
        createVariant("ci") {
            add("rootDebug")
        }
    }
}

// Kotzilla observability is configured after `computedVersionName` is resolved
// (see below). The plugin is applied conditionally above; the extension
// is configured there too.

allOpen {
    annotation("javax.inject.Singleton")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    parallel = true
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
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

// Configure the Kotzilla extension once `computedVersionName` is in scope.
// SDK 2.3.0+ supports Hilt natively (no Koin required) — see
// https://doc.kotzilla.io/docs/getstartedCustom/setupNoKoin.
// The `kotzilla.json` lives in :sharedLogic with `isDefault: true` so
// only that module owns it; the app module applies the plugin for
// ContentProvider auto-injection + Android-variant instrumentation.
if (kotzillaEnabled) {
    val kotzillaExt = extensions.getByName("kotzilla")
    kotzillaExt.withGroovyBuilder {
        setProperty("versionName", computedVersionName)
        setProperty(
            "displayLogs",
            providers.gradleProperty("kotzilla.verbose").orNull.toBoolean(),
        )
    }
}

// ---------------------------------------------------------------------------
// 16 KB LOAD-segment realignment for prebuilt Iroh / Nimbus JNI libraries
// (letta-mobile, 2026-08-09)
//
// The prebuilt libiroh_ffi.so (computer.iroh:iroh-android:1.1.0 AAR) and
// libjnidispatch.so (Nimbus JOSE/JWT) ship with 4 KB ELF alignment, which
// Google Play rejects for apps targeting Android 15+ starting Nov 1, 2025
// (developer.android.com/16kb-page-size). 1.1.0 is the latest iroh-android
// release on Maven Central, and the .so inside the AAR is byte-identical
// to the one we vendor in src/rootDebug/jniLibs/, so a version bump would
// not fix the warning.
//
// We rewrite the program headers in place to lift each PT_LOAD to the next
// 16 KB-aligned offset, padding with zero bytes between segments where
// needed. This is the same trick Chromium / Flutter use for the same
// problem: we don't vendor the upstream Rust / C source for these libs
// (iroh-ffi is not built here, Nimbus's libjnidispatch has no public
// source), so relinking with -Wl,-z,max-page-size=16384 is not an option.
//
// Idempotent: realign-16kb.py is a no-op when all PT_LOAD offsets are
// already 16 KB-aligned, so repeated builds don't churn inputs.
//
// Falls back gracefully if Python 3 is missing: prints a one-time warning
// and leaves the prebuilt .so files alone, mirroring the
// `scripts/build-android-*.sh` pattern (when absent, the runtime keeps
// the feature disabled instead of failing the build).
// ---------------------------------------------------------------------------
val realign16KbJniLibs = tasks.register("realign16KbJniLibs") {
    group = "build"
    description = "Rewrites prebuilt .so files under src/rootDebug/jniLibs/ for 16 KB LOAD alignment."
    notCompatibleWithConfigurationCache(
        "Resolves Python from the host PATH at execution time."
    )
    val scriptFile = project.layout.projectDirectory.file("scripts/realign-16kb.py")
    inputs.file(scriptFile)
    val jniLibsRoot = project.layout.projectDirectory.dir("src/rootDebug/jniLibs")
    // Track every .so under jniLibs as both inputs and outputs so Gradle's
    // incremental cache sees content changes and re-runs when needed.
    val soFiles = fileTree(jniLibsRoot) {
        include("**/*.so")
    }
    soFiles.forEach { inputs.file(it) }
    outputs.files(soFiles)
    doLast {
        val python = listOf("python3", "python", "py").firstOrNull { cmd ->
            try {
                val probe = ProcessBuilder(cmd, "-c", "import sys; sys.exit(0)")
                    .redirectErrorStream(true)
                    .start()
                probe.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        }
        if (python == null) {
            logger.warn(
                "[realign16KbJniLibs] Python 3 not found on PATH; skipping " +
                    "16 KB realignment. Prebuilt .so files in " +
                    "src/rootDebug/jniLibs/ will keep their 4 KB alignment, " +
                    "and app-play-*.apk will be flagged by Play Console."
            )
            return@doLast
        }
        jniLibsRoot.asFileTree.forEach { soFile ->
            if (!soFile.isFile) return@forEach
            val result = try {
                val proc = ProcessBuilder(
                    python,
                    scriptFile.asFile.absolutePath,
                    soFile.absolutePath,
                )
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor()
            } catch (_: Exception) {
                -1
            }
            if (result != 0) {
                logger.warn(
                    "[realign16KbJniLibs] realignment failed for ${soFile.name} " +
                        "(exit $result); the prebuilt .so will keep its " +
                        "original alignment."
                )
            }
        }
    }
}

// Hook the realignment task into every merge*JniLibFolders / merge*NativeLibs
// task. These are the AGP tasks that walk src/rootDebug/jniLibs/ and copy
// the .so files into the APK staging directory; running realign16KbJniLibs
// before them guarantees the staged files are already 16 KB-aligned.
//
// Unlike the embedded-LettaCode prebuilts (which only matter when the
// com.letta.mobile.embedded-runtime plugin's native flag is on), the Iroh / Nimbus prebuilts
// are required by every variant that exposes the Iroh transport, so this
// dependsOn runs unconditionally.
tasks.configureEach {
    if (
        (name.startsWith("merge") && name.endsWith("JniLibFolders")) ||
        (name.startsWith("merge") && name.endsWith("NativeLibs"))
    ) {
        dependsOn(realign16KbJniLibs)
        dependsOn(verifyJniLibSanity)
    }
}

// ---------------------------------------------------------------------------
// Post-realignment ELF sanity check (letta-mobile, 2026-08-10)
//
// Runs AFTER realign16KbJniLibs but BEFORE the merge*JniLibFolders task
// copies the .so files into the APK staging directory. Verifies every
// realigned .so in src/rootDebug/jniLibs/ has the dynamic-metadata tables
// the linker needs to load it (DT_HASH or DT_GNU_HASH + DT_STRTAB + DT_SYMTAB +
// DT_DYNAMIC, all non-null). The existing check-elf-16kb-alignment.sh only
// checks LOAD-segment alignment; it can't catch the
// "DT_HASH/DT_GNU_HASH was clobbered" case that the user hit in fire #53
// (the .so WAS 16 KB-aligned but the dynamic linker still couldn't read
// the symbol hash table, so the app died with SIGSEGV at 0.79s).
//
// Exit codes:
//   0 - all .so files have the required dynamic metadata
//   2 - one or more .so files are missing DT_HASH/DT_GNU_HASH/DT_STRTAB/
//       DT_SYMTAB/DT_DYNAMIC
//
// On failure this task throws GradleException, which fails the build.
// The .so files are NOT deleted (so the dev can still inspect them with
// llvm-readelf to see what went wrong); the user sees the actual table
// names that are missing in the build output.
// ---------------------------------------------------------------------------
val verifyJniLibSanity = tasks.register("verifyJniLibSanity") {
    group = "build"
    description = "Refuses to ship .so files missing DT_HASH/DT_GNU_HASH/DT_STRTAB/DT_SYMTAB (fire #53 crash)."
    notCompatibleWithConfigurationCache(
        "Resolves llvm-readelf from the AGP NDK toolchain at execution time."
    )
    // Explicit dependency on realign16KbJniLibs: both tasks read the
    // same .so files in src/rootDebug/jniLibs/, so Gradle's implicit
    // dependency detection can't disambiguate which task produces those
    // files. Without this dependsOn the configuration cache rejects the
    // build with "Property has implicit dependency" errors.
    dependsOn(realign16KbJniLibs)
    val scriptFile = project.layout.projectDirectory.file("scripts/so-sanity-check.sh")
    inputs.file(scriptFile)
    val jniLibsRoot = project.layout.projectDirectory.dir("src/rootDebug/jniLibs")
    val soFiles = fileTree(jniLibsRoot) {
        include("**/*.so")
    }
    soFiles.forEach { inputs.file(it) }
    // No outputs.files() here -- this task only reads the .so files
    // and raises a GradleException on failure; it does not modify them.
    // The previous outputs.files(soFiles) declaration created a cycle
    // with realign16KbJniLibs (which also lists the same files as
    // outputs), which Gradle's configuration cache rejects.
    doLast {
        // Resolve llvm-readelf from the AGP NDK toolchain when possible
        // (CI runners rarely have llvm-readelf on PATH; this matches the
        // path AGP uses for llvm-objdump). Falls back to PATH for hosts
        // that have it installed system-wide.
        val ndkRootEnv = System.getenv("ANDROID_NDK_HOME") ?: ""
        val sdkRootEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: ""
        // Probe for any installed NDK by globbing the SDK's ndk/ directory.
        // The CI runner at $ANDROID_HOME/ndk/27.3.13750724 wasn't covered
        // by the previous hardcoded list, so we now scan for any installed
        // NDK version and pick the first one. Falls back to fixed paths
        // when the SDK root isn't readable.
        val sdkNdkDir = if (sdkRootEnv.isNotBlank()) file("$sdkRootEnv/ndk") else null
        val ndkRootsFromSdk = sdkNdkDir?.listFiles { f -> f.isDirectory }
            ?.map { "${it.absolutePath}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" }
            ?: emptyList()
        val ndkCandidates = (listOf(
            "$ndkRootEnv/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf",
            "$ndkRootEnv/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf.exe",
            "/opt/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf",
            "/opt/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf",
            "/opt/stacks/ndk-r26d/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf",
        ) + ndkRootsFromSdk).filter { it.isNotBlank() }
        val readelf = ndkCandidates.firstOrNull { path ->
            file(path).exists() && file(path).canExecute()
        } ?: listOf("llvm-readelf", "readelf").firstOrNull { cmd ->
            try {
                val probe = ProcessBuilder(cmd, "--version")
                    .redirectErrorStream(true)
                    .start()
                probe.waitFor() == 0
            } catch (_: Exception) {
                false
            }
        } ?: run {
            // If we can't find llvm-readelf at all, that itself is a real
            // failure -- not "skip the check." Letting the build proceed
            // would silently lose the fire #53 protection, so surface
            // the missing dependency as a GradleException instead.
            throw GradleException(
                "[verifyJniLibSanity] llvm-readelf not found. Tried: " +
                    ndkCandidates.joinToString(", ") +
                    ", and PATH lookup for 'llvm-readelf'/'readelf'. " +
                    "Set ANDROID_NDK_HOME to your NDK install root (e.g. " +
                    "/opt/android-sdk/ndk/28.2.13676358) or add llvm-readelf " +
                    "to PATH. Without readelf we cannot enforce the " +
                    "DT_HASH/DT_GNU_HASH fire #53 guard."
            )
        }

        val failures = mutableListOf<String>()
        val failureDetails = mutableListOf<String>()
        // Capture each .so's stderr to a SEPARATE file, NOT the same
        // tmpOut that gets truncated each iteration. Otherwise the
        // LAST iteration's stderr overwrites earlier ones, and we lose
        // diagnostic information when multiple files fail.
        val tmpOutPattern = layout.buildDirectory.dir("verifyJniLibSanity-out")
        jniLibsRoot.asFileTree.forEach { soFile ->
            if (!soFile.isFile) return@forEach
            // Per-.so output file: each .so gets its own stderr captured
            // to its own file. This is critical because the previous
            // single-tmpOut design overwrote the file each iteration,
            // so only the LAST .so's stderr survived. With per-.so files,
            // we preserve every failed .so's diagnostic.
            val tmpOut = tmpOutPattern.get().asFile.resolve("${soFile.name}.txt")
            tmpOut.parentFile.mkdirs()
            val result = try {
                // Call the script with two positional args:
                //   $1 = path to llvm-readelf
                //   $2 = path to the .so to verify
                // (The previous build passed 3 args -- readelf, script, so --
                // which made the script treat the script-path as the readelf
                // and the script-path as the .so file, causing it to invoke
                // readelf against itself and report "not a valid object file"
                // for every .so file.)
                val proc = ProcessBuilder(
                    scriptFile.asFile.absolutePath,
                    readelf,
                    soFile.absolutePath,
                )
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tmpOut))
                    .start()
                proc.waitFor()
            } catch (e: Exception) {
                // Capture the exception type and message so the build
                // log shows WHY the script failed to spawn. Common causes
                // in CI: file not found (script path wrong), permission
                // denied (script not +x on a host that requires it), or
                // working directory not existing.
                val tmpErr = tmpOutPattern.get().asFile.resolve("${soFile.name}.err.txt")
                tmpErr.writeText(
                    "Exception type: ${e.javaClass.name}\n" +
                        "Message: ${e.message}\n" +
                        "Stack trace:\n${e.stackTraceToString()}\n"
                )
                -1
            }
            if (result != 0) {
                failures.add(soFile.name)
                val tmpErr = tmpOutPattern.get().asFile.resolve("${soFile.name}.err.txt")
                val detail = if (tmpOut.exists() && tmpOut.length() > 0) {
                    tmpOut.readText().trim()
                } else if (tmpErr.exists()) {
                    "SCRIPT FAILED TO SPAWN: ${tmpErr.readText().trim()}"
                } else {
                    "(no output captured)"
                }
                failureDetails.add("${soFile.name} (exit=$result): $detail")
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "[verifyJniLibSanity] the following .so files are missing " +
                    "DT_HASH/DT_GNU_HASH/DT_STRTAB/DT_SYMTAB/DT_DYNAMIC and will " +
                    "fail to dlopen() at runtime:\n  " + failures.joinToString("\n  ") +
                    "\n\nScript stderr:\n  " + failureDetails.joinToString("\n  ") +
                    "\n\nThis is the bookend check to check-elf-16kb-alignment.sh: " +
                    "the alignment is OK, but the dynamic linker needs the hash + " +
                    "strtab + symtab + dynamic tables to load the .so. Re-run " +
                    "realig-16kb.py with RUST_BACKTRACE=1 to find the bug, or " +
                    "revert to the pre-realign .so on build host.\n\nTo skim just the " +
                    "problem, run on the host:\n  $readelf -d <path-to-.so>"
            )
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug").withFlavor("distribution" to "root")) { variant ->
        // Keep the existing root development install/data separate from the Play identity.
        variant.applicationId.set("com.letta.mobile.dev")
    }
}

android {
    // Play identity freeze (letta-mobile-h0b1h.1): store package is Meridian.
    // Kotlin source packages remain com.letta.mobile; R/BuildConfig follow this namespace.
    namespace = "ca.oculair.meridian"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ca.oculair.meridian"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
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
                it.forkEvery = 20L
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
        // letta-mobile-by4vm.34: 238 orphaned strings were removed after the
        // hard cut. Android lint is not a CI gate (only release lint-vital,
        // which checks fatal issues), so this is a warning rather than an
        // error: promoting it would need a baseline for every other unused
        // drawable/layout/raw resource without anything enforcing it. Run
        // `./gradlew :app:lintRootDebug` to see new orphans.
        warning += "UnusedResources"
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
    }

    sourceSets {
        getByName("test") {
            kotlin.directories += "src/test/java"
        }
        getByName("main") {
            // Iroh QUIC transport native lib (libiroh_ffi.so + libjnidispatch.so)
            // is required by every build variant that exposes Iroh transport;
            // it must not be coupled to the unrelated embedded LettaCode native flag.
            jniLibs.directories.add(file("src/rootDebug/jniLibs").absolutePath)
        }
        // The `benchmark` buildType (macrobenchmark target) uses the same
        // no-op DebugPerformanceMonitor as release so benchmarks measure
        // the production code path, not the debug instrumentation stack.
        getByName("benchmark") {
            kotlin.directories += "src/release/java"
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
        )
    }
}

dependencies {
    implementation(project(":core:android-data"))
    testImplementation(project(":core:testutil"))
    implementation(project(":designsystem"))
    implementation(project(":feature-chat"))
    // Live plugin pages on the canvas: the sandboxed WebView host (letta-mobile-s416w.13).
    implementation(project(":plugin-view"))
    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.molecule.runtime)
    // The Rive mascot renderer (letta-mobile-8jtf3): the agent's avatar in the list, the chat top
    // bar and beside the composer. Ships with the app; the ~1.3 MB native runtime is the cost.
    implementation(project(":avatar:renderer-rive"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.metrics.performance)
    debugImplementation(libs.leakcanary.android)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // CameraX (letta-mobile-g2d2i: QR pairing scanner). Versions that are
    // centralized live in gradle/libs.versions.toml. Deliberately ImageAnalysis +
    // zxing:core (already a transitive sharedLogic dependency via core:android-data
    // -> sharedLogic) instead of ML Kit barcode scanning: ML Kit either
    // bundles a large model (APK bloat) or requires Google Play Services
    // (unbundled), which the Root/Sideload flavors cannot assume.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // DataStore + Encrypted SharedPreferences
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Coil (image loading)
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.ktor3)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // On-device LLM inference for explicitly constructed .litertlm engines.
    implementation(libs.litertlm.android)

    // Drag-to-reorder for Compose
    implementation(libs.reorderable)

    // Immutable collections for Compose stability
    implementation(libs.kotlinx.collections.immutable)

    // Fuzzy search
    implementation(libs.fuzzywuzzy)

    // Vico charts
    implementation(libs.vico.compose.m3)

    // Timeline visualization
    implementation(libs.jetlime)

    // Open source notices
    implementation(libs.aboutlibraries.compose.m3)

    // Paging 3
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.paging.compose)

    // Background sync
    implementation(libs.androidx.work.runtime.ktx)

    // Sentry error tracking — initialized programmatically via
    // androidx.startup in SentryInitializer. See letta-mobile-o7ob.7.
    implementation(libs.sentry.android)
    implementation(libs.androidx.startup.runtime)

    // Baseline Profile installer — reads the bundled profile and warms
    // AOT compilation on first launch. See letta-mobile-o7ob.2.1 and
    // letta-mobile-o7ob.2.4.
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    // Testing
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)

    // Hilt testing
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testRuntimeOnly(libs.junit.vintage.engine)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    // NotebookLocalStore exposes DocumentId in its API; instrumentation needs it to compile.
    androidTestImplementation(libs.automerge)
    // Align the app's dependency constraints with AndroidX Test 1.3/1.7.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.concurrent.futures.ktx)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
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
    jvmArgs(testTask.jvmArgs)
    forkEvery = 1L

    useJUnitPlatform {
        includeEngines("junit-vintage")
    }
    filter {
        includeTestsMatching("*ScreenshotTest")
        isFailOnNoMatchingTests = false
    }
}
