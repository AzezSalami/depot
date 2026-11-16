package app.depot.store

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import app.depot.core.AppState
import app.depot.core.Busy
import app.depot.core.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { WearScreen(application as DepotWearApp, this) } }
    }

    /** Returns false if this watch has no screen for the permission. */
    fun openInstallPermission(): Boolean = runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }.isSuccess
}

@Composable
private fun WearScreen(app: DepotWearApp, activity: MainActivity) {
    val depot = app.depot
    val all by depot.apps.collectAsStateWithLifecycle()
    val refreshing by depot.refreshing.collectAsStateWithLifecycle()
    val configVersion by depot.configVersion.collectAsStateWithLifecycle()
    val hasToken = remember(configVersion) { depot.hasToken }
    val apps = all.filter { it.status != Status.OtherDevice && it.status != Status.NoAsset }
    var note by remember { mutableStateOf<String?>(null) }
    val listState = rememberScalingLazyListState()

    LifecycleResumeEffect(Unit) {
        depot.publish()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { pullConfig(activity) }
    }
    LaunchedEffect(configVersion) { if (depot.hasToken) app.scope.launch { depot.refresh() } }

    fun act(state: AppState) {
        if (state.busy != null) return
        when (state.status) {
            Status.NotInstalled, Status.UpdateAvailable -> {
                if (!depot.canInstallPackages() && activity.openInstallPermission()) {
                    note = "Allow Depot to install apps, then tap the app again."
                    return
                }
                note = null
                app.scope.launch { depot.install(state.repo.key) }
            }
            Status.UpToDate -> state.packageName?.let { depot.launchIntent(it) }?.let { activity.startActivity(it) }
            else -> {}
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Depot") } }
            note?.let { item { Hint(it) } }
            when {
                !hasToken -> {
                    item { Hint("Open Depot on your phone and add your GitHub token. It is sent here automatically.") }
                    item {
                        Chip(
                            label = { Text("Check phone again") },
                            onClick = { app.scope.launch { withContext(Dispatchers.IO) { pullConfig(activity) } } },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                apps.isEmpty() -> item {
                    Hint("No watch apps yet. Add a repository on your phone and set it to install on the watch.")
                }
                else -> items(apps, key = { it.repo.key }) { state ->
                    val actionable = state.status == Status.NotInstalled || state.status == Status.UpdateAvailable
                    Chip(
                        label = { Text(state.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                state.error ?: statusLine(state),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (state.error != null) MaterialTheme.colors.error else Color.Unspecified,
                            )
                        },
                        onClick = { act(state) },
                        colors = if (actionable) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (hasToken) {
                item {
                    Chip(
                        label = { Text(if (refreshing) "Checking…" else "Check for updates") },
                        onClick = { if (!refreshing) app.scope.launch { depot.refresh() } },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.caption1,
    )
}

private fun statusLine(state: AppState): String = when (val busy = state.busy) {
    is Busy.Downloading -> "Downloading ${(busy.fraction * 100).toInt()}%"
    Busy.Installing -> "Installing…"
    Busy.Checking -> "Checking…"
    null -> when (state.status) {
        Status.NotInstalled -> "Tap to install ${state.release?.tag.orEmpty()}"
        Status.UpdateAvailable -> "Tap to update to ${state.release?.tag.orEmpty()}"
        Status.UpToDate -> "Up to date · ${state.installedVersion.orEmpty()}"
        Status.NoRelease -> "No release yet"
        Status.Unknown -> "Not checked yet"
        else -> ""
    }
}
