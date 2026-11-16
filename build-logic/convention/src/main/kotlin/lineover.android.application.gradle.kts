import dev.mark.lineover.buildlogic.DEBUG_ABIS
import dev.mark.lineover.buildlogic.RELEASE_ABIS
import dev.mark.lineover.buildlogic.configureAndroidCommon
import dev.mark.lineover.buildlogic.lib
import dev.mark.lineover.buildlogic.libs
import dev.mark.lineover.buildlogic.version

plugins {
    id("com.android.application")
    id("lineover.quality")
}

android {
    configureAndroidCommon(this)

    defaultConfig {
        targetSdk = libs.version("targetSdk").toInt()
    }

    buildTypes {
        debug { ndk { abiFilters += DEBUG_ABIS } }
        release { ndk { abiFilters += RELEASE_ABIS } }
    }

    lint {
        // :app:lintDebug analyses every module it depends on in one pass.
        checkDependencies = true
    }
}

dependencies {
    "testImplementation"(libs.lib("junit"))
}
