plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

group = "com.letta.mobile.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.android.tools.build:gradle:9.2.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        create("architectureGraph") {
            id = "com.letta.mobile.architecture-graph"
            implementationClass = "com.letta.mobile.architecture.ArchitectureGraphPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
