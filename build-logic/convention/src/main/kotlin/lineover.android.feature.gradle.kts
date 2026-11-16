// A feature module: Android library + Compose + screenshot tests, depending on the
// shared core modules. Features never depend on each other.
plugins {
    id("lineover.android.library")
    id("lineover.android.compose")
    id("lineover.roborazzi")
}

dependencies {
    "implementation"(project(":core:designsystem"))
    "implementation"(project(":core:ui"))
    "implementation"(project(":core:model"))
}
