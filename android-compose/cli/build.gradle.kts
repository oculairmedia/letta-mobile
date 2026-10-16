plugins {
    // letta-mobile-by4vm.57: plain Kotlin/JVM. The module uses no Android APIs, and being an
    // Android library forced `:cli:run` to borrow the debug unit-test classpath (Android stubs)
    // and to drag kotlinx-coroutines-android. It now consumes :sharedLogic's `jvm` target.
    // Sources stay under src/*/java (the Kotlin JVM plugin compiles .kt there by default) so
    // the history and the changed-file guardrails are not churned by a pure directory move.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    compilerOptions {
        // JVM 21: computer.iroh:iroh:1.0.0 (via :sharedLogic's jvm target) is JVM 21+ only.
        // Matches :iroh-wrapper-cli, :appserver-cli and :desktop.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    applicationName = "meridian"
    mainClass.set("com.letta.mobile.cli.Main")
    // The forked JVM is what loads libiroh_ffi (JNI); keep it green if a future JDK flips
    // restricted-native-access warnings into errors.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":sharedLogic"))
    // letta-mobile-zsgad: `app-server-serve-iroh` now lives in the pure-JVM
    // :iroh-wrapper-cli module so it can ship as an installable distribution
    // (at the time this was an Android library module). The developer `meridian` CLI keeps
    // exposing the same subcommand by consuming it from there.
    implementation(project(":iroh-wrapper-cli"))

    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.iroh)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1536m"
    jvmArgs("-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384m")
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

// `./gradlew :cli:run -PcliArgs="stream --message hello"` (the scripts under scripts/ use
// -PcliArgs; Gradle's own `--args` also works and takes precedence when given).
tasks.named<JavaExec>("run") {
    providers.gradleProperty("cliArgs").orNull?.let { args(splitCliArgs(it)) }
    standardInput = System.`in`
    outputs.upToDateWhen { false }
}
