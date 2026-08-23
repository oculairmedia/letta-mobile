import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.library")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogInt(name: String): Int =
    catalog.findVersion(name).orElseThrow { IllegalStateException("Missing catalog version $name") }
        .requiredVersion
        .toInt()

android {
    compileSdk = catalogInt("compileSdk")
    defaultConfig {
        minSdk = catalogInt("minSdk")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

fun configureKotlin17() {
    extensions.findByType<KotlinAndroidProjectExtension>()?.compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.android") { configureKotlin17() }
pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") { configureKotlin17() }
afterEvaluate { configureKotlin17() }
