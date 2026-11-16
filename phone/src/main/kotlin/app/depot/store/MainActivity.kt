package app.depot.store

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as DepotApp
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                var onSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = onSettings) { onSettings = false }
                if (onSettings) {
                    SettingsScreen(app, onBack = { onSettings = false })
                } else {
                    AppsScreen(app, onOpenSettings = { onSettings = true })
                }
            }
        }
    }
}

internal fun ComponentActivity.openInstallPermission() {
    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
}
