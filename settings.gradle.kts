pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LineOver"

// The module list is fixed forever (docs/ARCHITECTURE.md). Do not add or rename modules.
include(":app")
include(":core:designsystem")
include(":core:ui")
include(":core:model")
include(":core:engine")
include(":core:engine-native")
include(":core:io")
include(":core:ml")
include(":feature:home")
include(":feature:editor")
include(":feature:export")
include(":feature:settings")
include(":benchmark")
include(":baselineprofile")
