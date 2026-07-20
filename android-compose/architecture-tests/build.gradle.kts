plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("com.lemonappdev:konsist:0.17.3")
    testImplementation("com.tngtech.archunit:archunit:1.4.2")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.withType<Test>().configureEach {
    dependsOn(":core:ids:jvmMainClasses", ":core:domain:classes")
    useJUnitPlatform()
    systemProperty("architecture.projectRoot", rootProject.projectDir.parentFile.absolutePath)
}

tasks.test {
    filter {
        excludeTestsMatching("*RepositoryArchitectureTest")
    }
}

val architectureTest by tasks.registering(Test::class) {
    description = "Run advisory Kotlin source and JVM bytecode architecture checks."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching("*RepositoryArchitectureTest")
    }
    ignoreFailures = providers.gradleProperty("architecture.strict").orNull != "true"
    reports.junitXml.required.set(true)
    reports.html.required.set(true)
}
