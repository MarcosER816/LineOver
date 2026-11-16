import dev.mark.lineover.buildlogic.configureAndroidCommon
import dev.mark.lineover.buildlogic.libs
import dev.mark.lineover.buildlogic.version

// Shared setup for the com.android.test modules (:benchmark, :baselineprofile).
plugins {
    id("com.android.test")
    id("lineover.quality")
}

android {
    configureAndroidCommon(this)

    defaultConfig {
        targetSdk = libs.version("targetSdk").toInt()
    }
}
