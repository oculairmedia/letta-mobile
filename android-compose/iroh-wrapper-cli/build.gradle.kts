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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-websockets:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    // iroh is `implementation` (not `api`) inside sharedLogic's jvmAndAndroid
    // source set, so a JVM consumer must ask for the JNI artifact itself for it
    // to land in the distribution's lib/ directory.
    runtimeOnly("computer.iroh:iroh:1.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("computer.iroh:iroh:1.0.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.test {
    useJUnitPlatform()
}

// sharedLogic's KMP variants can contribute same-named resources; keep the
// distribution copy deterministic instead of failing the build.
tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
