plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.letta.mobile.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("apiDocumentation") {
            id = "com.letta.mobile.api-documentation"
            implementationClass = "com.letta.mobile.docs.ApiDocumentationPlugin"
        }
        create("architectureGraph") {
            id = "com.letta.mobile.architecture-graph"
            implementationClass = "com.letta.mobile.architecture.ArchitectureGraphPlugin"
        }
        create("embeddedRuntime") {
            id = "com.letta.mobile.embedded-runtime"
            implementationClass = "com.letta.mobile.embedded.EmbeddedRuntimePlugin"
        }
        create("kmpLibrary") {
            id = "com.letta.mobile.kmp.library"
            implementationClass = "com.letta.mobile.kmp.KmpLibraryPlugin"
        }
        create("jni16Kb") {
            id = "com.letta.mobile.jni-16kb"
            implementationClass = "com.letta.mobile.jni.Jni16KbPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
