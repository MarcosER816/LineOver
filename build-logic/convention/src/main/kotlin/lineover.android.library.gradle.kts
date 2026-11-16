import dev.mark.lineover.buildlogic.DEBUG_ABIS
import dev.mark.lineover.buildlogic.RELEASE_ABIS
import dev.mark.lineover.buildlogic.configureAndroidCommon
import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs

plugins {
    id("com.android.library")
    id("lineover.quality")
}

android {
    configureAndroidCommon(this)

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        debug { ndk { abiFilters += DEBUG_ABIS } }
        release { ndk { abiFilters += RELEASE_ABIS } }
    }
}

// Modules without instrumented tests do not build or install an empty test APK,
// which keeps connectedDebugAndroidTest on the emulator short.
val hasAndroidTests = file("src/androidTest").isDirectory
androidComponents {
    beforeVariants { variant ->
        variant.androidTest.enable = hasAndroidTests
    }
}

dependencies {
    "testImplementation"(libs.lib("junit"))
}
