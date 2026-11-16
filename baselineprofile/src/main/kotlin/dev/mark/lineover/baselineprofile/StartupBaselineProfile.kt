package dev.mark.lineover.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Generates the startup baseline profile of the app. */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() {
        val targetPackage =
            checkNotNull(InstrumentationRegistry.getArguments().getString("targetAppId")) {
                "targetAppId is passed by the androidx.baselineprofile plugin"
            }
        rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
        }
    }
}
