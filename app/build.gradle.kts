plugins {
    id("lineover.android.application")
    id("lineover.android.compose")
    id("lineover.kotlin.serialization")
    id("lineover.android.baselineprofile")
}

// Release signing comes from the environment (CI decodes the KEYSTORE_B64 secret
// to a temporary file). Without it the release build is signed with the debug key
// so that forks and secret-less runs still produce an installable APK.
val keystorePath: String? = providers.environmentVariable("LINEOVER_KEYSTORE_FILE").orNull
val hasReleaseKeystore = keystorePath != null && file(keystorePath).isFile

android {
    namespace = "dev.mark.lineover"

    defaultConfig {
        applicationId = "dev.mark.lineover"
        versionCode = 3
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = providers.environmentVariable("LINEOVER_KEYSTORE_PASS").get()
                keyAlias = providers.environmentVariable("LINEOVER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("LINEOVER_KEY_PASS").get()
            }
        }
    }

    buildTypes {
        debug {
            // Lets the debug build sit next to the release build on the same phone.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }
}

// src/debug provides DebugTools (the component gallery); every other build type gets
// the empty DebugTools from src/nonDebug, so release builds never contain the gallery.
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "debug") {
            variant.sources.kotlin?.addStaticSourceDirectory("src/nonDebug/kotlin")
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:io"))
    implementation(project(":core:ml"))
    implementation(project(":feature:home"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:export"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // The navigation flow also runs on the JVM (Robolectric) in the unit job.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
