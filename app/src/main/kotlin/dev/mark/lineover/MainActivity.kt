package dev.mark.lineover

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.mark.lineover.core.designsystem.LineOverTheme
import dev.mark.lineover.navigation.LineOverNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash shows the icon on the background colour until the first frame.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LineOverApp).container
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        setContent {
            LineOverTheme {
                LineOverNavigation(container, versionName, DebugTools.gallery)
            }
        }
    }
}
