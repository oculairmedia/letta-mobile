package com.letta.mobile.embedded

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Android DSL wiring for the embedded runtime. Runs in finalizeDsl, i.e. after app/build.gradle.kts's
 * own `android {}` block, which keeps the old ordering (app jniLibs first, embedded ones after).
 */
internal fun configureEmbeddedRuntimeAndroid(
    project: Project,
    pins: EmbeddedRuntimePins,
    flags: EmbeddedRuntimeFlags,
    dirs: EmbeddedRuntimeDirs,
) {
    project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).finalizeDsl { android ->
        val nativeEnabled = flags.nativeEnabled.get()
        android.addBuildConfigFields(pins, nativeEnabled, flags.assetsEnabled.get())
        if (flags.assetsEnabled.get()) {
            android.sourceSets.getByName("main").assets.directories.add(dirs.assets.get().asFile.absolutePath)
        }
        if (nativeEnabled) android.addNativeRuntime(project, dirs)
    }
}

private fun ApplicationExtension.addBuildConfigFields(pins: EmbeddedRuntimePins, nativeEnabled: Boolean, assetsEnabled: Boolean) {
    defaultConfig.buildConfigField("boolean", "EMBEDDED_LETTACODE_NATIVE_ENABLED", nativeEnabled.toString())
    defaultConfig.buildConfigField("boolean", "EMBEDDED_LETTACODE_ASSETS_ENABLED", assetsEnabled.toString())
    defaultConfig.buildConfigField("String", "EMBEDDED_LETTACODE_VERSION", "\"${pins.lettaCodeVersion}-r${pins.assetRevision}\"")
    defaultConfig.buildConfigField("String", "EMBEDDED_LETTACODE_INTEGRITY", "\"${pins.lettaCodeIntegrity}\"")
}

private fun ApplicationExtension.addNativeRuntime(project: Project, dirs: EmbeddedRuntimeDirs) {
    // letta-mobile-by4vm.110 D2: the full-fat (embedded) build is arm64-v8a only. The NDK helpers
    // (git/bash/curl/node CLI) exist only for arm64, so another ABI would ship libnode without the
    // git that letta-code's memfs needs.
    defaultConfig.ndk.abiFilters += "arm64-v8a"
    defaultConfig.externalNativeBuild.cmake.arguments +=
        "-DLETTACODE_LIBNODE_DIR=${dirs.libnode.get().asFile.absolutePath.replace("\\", "/")}"
    defaultConfig.externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_shared"
    // libgit.so is exec()'d as a program (memfs git), which requires a real file in
    // nativeLibraryDir — loading straight from the APK (the non-legacy default) only supports dlopen.
    packaging.jniLibs.useLegacyPackaging = true
    val jniLibs = sourceSets.getByName("main").jniLibs.directories
    jniLibs.add(dirs.libnode.get().asFile.resolve("bin").absolutePath)
    // NDK helpers shipped as lib*.so so they land in the executable nativeLibraryDir (filesDir is
    // noexec); built by scripts/build-android-*.sh (gitignored). git (memfs; absent -> local memfs
    // disabled), GNU bash (absent -> /system/bin/sh aliased under the name), a curl-compatible
    // helper that delegates HTTP(S) through AndroidNetworkBridge, and a node CLI wrapper linked
    // against libnode so local tools can run `node -e` scripts.
    listOf("embedded-git", "embedded-bash", "embedded-curl", "embedded-node-cli").forEach { dir ->
        jniLibs.add(project.file("libs/$dir").absolutePath)
    }
    externalNativeBuild.cmake.path = project.file("src/main/cpp/CMakeLists.txt")
}
