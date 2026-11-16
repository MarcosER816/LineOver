plugins {
    id("lineover.android.application")
    id("lineover.android.compose")
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
        versionCode = 1
        versionName = "0.0.1"
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
