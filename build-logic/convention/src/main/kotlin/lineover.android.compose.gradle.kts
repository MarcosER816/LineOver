import com.android.build.api.dsl.CommonExtension
import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs

// Jetpack Compose for an Android module. Apply after lineover.android.application
// or lineover.android.library.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<CommonExtension>("android") {
    buildFeatures.compose = true
}

dependencies {
    "implementation"(platform(libs.lib("androidx-compose-bom")))
    "implementation"(libs.lib("androidx-compose-ui"))
    "implementation"(libs.lib("androidx-compose-foundation"))
    "implementation"(libs.lib("androidx-compose-material3"))
    "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
    "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
}
