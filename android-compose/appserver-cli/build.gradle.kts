import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.AbstractCopyTask
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    compilerOptions {
        // JVM 21: this module consumes sharedLogic, which brings the Iroh QUIC
        // transport binding (computer.iroh:iroh:1.0.0, JVM 21+ only) onto the
        // runtime classpath. Matches the desktop module's target for the same
        // reason.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-Xcontext-parameters",
        )
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    applicationName = "meridian-app-server"
    mainClass.set("com.letta.mobile.appservercli.Main")
}

dependencies {
    implementation(project(":sharedLogic"))

    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<AbstractCopyTask>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named<CreateStartScripts>("startScripts") {
    enabled = false
}
