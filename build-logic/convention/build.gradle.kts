plugins {
    `kotlin-dsl`
}

group = "dev.mark.lineover.buildlogic"

// Everything a convention plugin applies must be on this classpath. Putting the
// Kotlin Gradle plugin here is also what selects the Kotlin version used by
// AGP's built-in Kotlin support (we never apply org.jetbrains.kotlin.android).
dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.compose.gradlePlugin)
    implementation(libs.kotlin.serialization.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.roborazzi.gradlePlugin)
    implementation(libs.androidx.baselineprofile.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.room.gradlePlugin)
}
