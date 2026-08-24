pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // Kotzilla SDK artifacts (e.g. kotzilla-sdk-compose-jvm) are published
        // to the Gradle Plugin Portal, not Maven Central. The plugin itself
        // resolves via pluginManagement above; runtime SDK deps need this
        // fallback to find their per-platform variants.
        gradlePluginPortal()
        ivy("https://nodejs.org/dist/") {
            name = "Node.js distributions"
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources {
                artifact()
            }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources {
                artifact()
            }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources {
                artifact()
            }
        }
    }
}

rootProject.name = "LettaMobile"
include(":app")
include(":core:ids")
include(":core:runtime")
include(":core:android-data")
include(":core:testutil")
include(":avatar:core")
include(":avatar:catalog")
include(":avatar:asset-pipeline")
include(":avatar:renderer-web")
include(":sharedLogic")
include(":sharedUI")
include(":designsystem")
include(":feature-chat")
include(":feature-editagent")
include(":desktop")
include(":web")
include(":cli")
include(":appserver-cli")
include(":iroh-wrapper-cli")
include(":macrobenchmark")
include(":baselineprofile")
include(":architecture-tests")
