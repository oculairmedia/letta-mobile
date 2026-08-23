import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.AbstractCopyTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// ---------------------------------------------------------------------------
// letta-mobile-zsgad: the packaged Iroh wrapper distribution.
// ---------------------------------------------------------------------------
// The production `meridian-iroh-wrapper.service` used to launch the wrapper from
// a captured `java -cp "$(cat /etc/meridian/iroh-wrapper-classpath.txt)"` string:
// 280 entries pointing into gitignored `build/intermediates/` directories of a
// git worktree plus `~/.gradle` caches. Deleting that worktree (or bumping a
// dependency version) silently broke the service on the next restart.
//
// The captured-classpath hack existed because `:cli` is an ANDROID library
// module: AGP publishes no runnable JVM runtime classpath, so there was nothing
// to `installDist`. This module is the pure-JVM home for the wrapper entrypoint:
// the `application` plugin gives it `installDist` / `distTar` / `distZip`, i.e.
// a self-contained `lib/` of real jars plus a `bin/` launcher.
//
// Everything the wrapper needs already lives in `:sharedLogic`, which is KMP
// with a `jvm()` target (the Iroh binding sits in the `jvmAndAndroid` source
// set, shared by the `jvm` and `android` targets), so no Android artifact is
// involved. `:cli` now depends on THIS module for the wrapper command rather
// than the other way around — the sources moved, they were not duplicated.
//
// Side effect worth naming: being JVM-only (no `android.jar`) makes
// `java.lang.ProcessHandle` available here. That is exactly the API whose
// absence caused lgns8.18's FU1 (process-group SIGKILL of orphaned App Server
// children) to be declined — see the KDoc on `OwnedAppServerProcess`. FU1 is
// NOT implemented here; this module only makes it possible.
kotlin {
    compilerOptions {
        // JVM 21: computer.iroh:iroh:1.0.0 (pulled in transitively via
        // :sharedLogic) is JVM 21+ only. Matches :appserver-cli and :desktop.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The deployment runbook requires the release artifact to carry the git commit
// it was built from, so an operator can map /opt/meridian/iroh-wrapper/current
// back to a tree. Falls back to "dev" outside a git checkout (or on a shallow
// export) rather than failing the build; `installDist` is unaffected either way
// because it installs into build/install/<applicationName>.
version = providers.exec {
    commandLine("git", "rev-parse", "--short=12", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("").map { sha ->
    if (sha.isEmpty()) "dev" else sha
}.get()

application {
    applicationName = "meridian-iroh-wrapper"
    mainClass.set("com.letta.mobile.wrapper.Main")
    // The forked JVM is what loads libiroh_ffi (JNI); keep it green if a future
    // JDK flips restricted-native-access warnings into errors. Mirrors the
    // jvmArgs on `:cli`'s JavaExec `run` task, which the captured-classpath
    // launcher also carried (`--enable-native-access=ALL-UNNAMED`).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8")
}

dependencies {
    api(project(":sharedLogic"))

    api("com.github.ajalt.clikt:clikt:5.1.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    // letta-mobile-bn008.6: the a2a wiring binds a SECOND Endpoint directly
    // (so we can add `/letta/a2a/0` alongside the app-server ALPN). Mirrors
    // sharedLogic's `implementation` of the same artifact — `implementation`
    // here keeps the surface tight while still letting the A2aWiring helper
    // reach Endpoint/EndpointOptions/SecretKey. It also covers the
    // distribution's runtime classpath (the KDoc on `irohNativeBindingIs
    // OnTheDistributionRuntimeClasspath` test pins this).
    //
    // N10 (PR #1125): the previous `runtimeOnly` and `testImplementation`
    // declarations of the same `computer.iroh:iroh:1.0.0` artifact were
    // redundant — `implementation` is on both the compile and runtime
    // classpaths, and `testImplementation` extends `implementation`. The
    // irohNativeBindingIsOnTheDistributionRuntimeClasspath test still passes
    // after the removal (it resolves `computer.iroh.Endpoint` through the
    // production `implementation` declaration).
    implementation("computer.iroh:iroh:1.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation(libs.kotlinx.coroutines.test)
    // letta-mobile-gw0h1: jvmTest round-trips the QR encoder through
    // ZXing's reader + BufferedImageLuminanceSource to prove the CLI's
    // PNG renderer produces a scannable image. The `core` jar comes
    // transitively from :sharedLogic's `api` declaration.
    testImplementation("com.google.zxing:javase:3.5.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.test {
    useJUnitPlatform()
    // letta-mobile-bn008.6: forward the opt-in flag for the live-QUIC a2a
    // build probe. Native bind through iroh-ffi is flaky in CI runners and
    // never useful for the default gate; A2aWiringTest gates the loopback
    // cases via JUnit Assume unless this is "true".
    System.getProperty("runIrohNativeE2E")?.let { systemProperty("runIrohNativeE2E", it) }
}

// sharedLogic's KMP variants can contribute same-named resources; keep the
// distribution copy deterministic instead of failing the build.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
