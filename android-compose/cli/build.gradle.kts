plugins {
    id("com.letta.mobile.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.letta.mobile.cli"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                it.maxHeapSize = "1536m"
                it.jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
            }
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "src/main/java"
        }
        getByName("test") {
            kotlin.directories += "src/test/java"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:android-data"))
    implementation(project(":sharedLogic"))
    // letta-mobile-zsgad: `app-server-serve-iroh` now lives in the pure-JVM
    // :iroh-wrapper-cli module so it can ship as an installable distribution
    // (this Android library module cannot). The developer `meridian` CLI keeps
    // exposing the same subcommand by consuming it from there.
    implementation(project(":iroh-wrapper-cli"))

    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2")
    implementation("computer.iroh:iroh:1.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

fun splitCliArgs(input: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuote = false
    var escaped = false
    input.forEach { c ->
        when {
            escaped -> {
                cur.append(c)
                escaped = false
            }
            inQuote && c == '\\' -> escaped = true
            c == '"' -> inQuote = !inQuote
            c == ' ' && !inQuote -> {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.clear()
                }
            }
            else -> cur.append(c)
        }
    }
    if (escaped) {
        cur.append('\\')
    }
    if (inQuote) {
        throw IllegalArgumentException("Unbalanced quotes in cliArgs")
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

// Custom task: `./gradlew :cli:run -PcliArgs="stream --message hello"`
tasks.register<JavaExec>("run") {
    description = "Run the letta-mobile CLI."
    group = "application"

    dependsOn("compileDebugUnitTestKotlin", "processDebugUnitTestJavaRes")
    mainClass.set("com.letta.mobile.cli.Main")
    val unitTestRuntime = tasks.named<Test>("testDebugUnitTest").get().classpath
    classpath(unitTestRuntime)
    classpath(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
    )

    args(splitCliArgs(providers.gradleProperty("cliArgs").orElse("").get()))
    standardInput = System.`in`
    // This forked JVM (not the daemon/client, which GRADLE_OPTS /
    // gradle.properties cover) is what actually loads libiroh_ffi; keep it
    // green if a future JDK flips native-access warnings to errors.
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    outputs.upToDateWhen { false }
}
