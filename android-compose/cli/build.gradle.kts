plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.letta.mobile.cli"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    implementation(project(":core:data"))

    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-okhttp:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.2")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

fun splitCliArgs(input: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuote = false
    input.forEach { c ->
        when {
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

    outputs.upToDateWhen { false }
}
