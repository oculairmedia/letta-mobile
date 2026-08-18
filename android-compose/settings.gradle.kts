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
include(":core:domain")
include(":core:data")
include(":core:testutil")
include(":avatar:core")
include(":avatar:catalog")
include(":avatar:asset-pipeline")
include(":avatar:renderer-web")
include(":sharedLogic")
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
