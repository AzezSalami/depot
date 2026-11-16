package app.depot.store

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.depot.core.AppState
import app.depot.core.Busy
import app.depot.core.RepoEntry
import app.depot.core.Status
import app.depot.core.Target
import app.depot.core.WatchApp
import app.depot.core.WatchLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(app: DepotApp, onOpenSettings: () -> Unit) {
    val depot = app.depot
    val activity = LocalActivity.current as ComponentActivity
    val apps by depot.apps.collectAsStateWithLifecycle()
    val refreshing by depot.refreshing.collectAsStateWithLifecycle()
    val configVersion by depot.configVersion.collectAsStateWithLifecycle()
    val hasToken = remember(configVersion) { depot.hasToken }
    val watchLink by app.watch.link.collectAsStateWithLifecycle()
    val watchState by app.watch.state.collectAsStateWithLifecycle()
    val watchRequests by app.watch.requests.collectAsStateWithLifecycle()
    val shareWithWatch = remember(configVersion) { depot.settings.syncToWatch }
    var adding by remember { mutableStateOf(false) }
    var needPermission by remember { mutableStateOf(false) }

    // Picks up installs and uninstalls that happened while the store was in the background.
    LifecycleResumeEffect(Unit) {
        depot.publish()
        // The watch may have come into range, or gone, while the store was in the background.
        app.watch.refresh()
        onPauseOrDispose {}
    }
    fun refresh() {
        app.scope.launch { depot.refresh() }
        // Also starts the watch app if it is not running, so its state here is current.
        app.watch.check()
    }
    LaunchedEffect(configVersion) { if (depot.hasToken) refresh() }

    fun install(keys: List<String>) {
        if (!depot.canInstallPackages()) {
            needPermission = true
            return
        }
        keys.forEach { key -> app.scope.launch { depot.install(key) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Depot") },
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { refresh() }, enabled = hasToken) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasToken) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add repository") },
                )
            }
        },
    ) { padding ->
        when {
            !hasToken -> Message(
                padding,
                "Connect GitHub",
                "Depot installs and updates apps from the releases of your private GitHub repositories. " +
                    "Add an access token to get started.",
                "Open settings",
                onOpenSettings,
            )

            apps.isEmpty() -> Message(
                padding,
                "No repositories yet",
                "Add a repository that publishes APK files in its GitHub releases.",
                "Add repository",
            ) { adding = true }

            else -> {
                val updates = apps.filter { it.status == Status.UpdateAvailable && it.busy == null }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (updates.size > 1) {
                        item(key = "update-all") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${updates.size} updates available", Modifier.weight(1f))
                                Button(onClick = { install(updates.map { it.repo.key }) }) { Text("Update all") }
                            }
                        }
                    }
                    items(apps, key = { it.repo.key }) { state ->
                        val onWatch = watchState?.app(state.repo.key)
                        AppCard(
                            state = state,
                            watch = if (WatchLink.hasWatchApp(state.repo, state.release, onWatch)) {
                                WatchRow(onWatch, watchLink, watchRequests[state.repo.key], shareWithWatch)
                            } else {
                                null
                            },
                            onWatchInstall = { app.watch.install(state.repo.key) },
                            onInstall = { install(listOf(state.repo.key)) },
                            onOpen = {
                                state.packageName?.let { depot.launchIntent(it) }?.let { activity.startActivity(it) }
                            },
                            canOpen = state.packageName?.let { depot.launchIntent(it) } != null,
                            onChange = { depot.updateRepo(it) },
                            onUninstall = { state.packageName?.let { activity.startActivity(depot.uninstallIntent(it)) } },
                            onRemove = { depot.removeRepo(state.repo.key) },
                        )
                    }
                }
            }
        }
    }

    if (adding) AddRepoDialog(app, onDismiss = { adding = false })
    if (needPermission) {
        AlertDialog(
            onDismissRequest = { needPermission = false },
            title = { Text("Allow installs") },
            text = { Text("Android needs your permission before Depot can install apps. Turn on \"Allow from this source\" on the next screen, then come back.") },
            confirmButton = {
                TextButton(onClick = { needPermission = false; activity.openInstallPermission() }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { needPermission = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Message(padding: PaddingValues, title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun AppCard(
    state: AppState,
    watch: WatchRow?,
    onWatchInstall: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    canOpen: Boolean,
    onChange: (RepoEntry) -> Unit,
    onUninstall: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var choosingTarget by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.repo.key,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options for ${state.label}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Install on: ${targetName(state.repo.target)}") },
                            onClick = { menu = false; choosingTarget = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.repo.prerelease) "Stop using pre-releases" else "Include pre-releases") },
                            onClick = { menu = false; onChange(state.repo.copy(prerelease = !state.repo.prerelease)) },
                        )
                        if (state.release != null) {
                            DropdownMenuItem(text = { Text("Release notes") }, onClick = { menu = false; showNotes = true })
                        }
                        if (state.installedVersion != null) {
                            DropdownMenuItem(text = { Text("Uninstall app") }, onClick = { menu = false; onUninstall() })
                        }
                        DropdownMenuItem(text = { Text("Remove from Depot") }, onClick = { menu = false; confirmRemove = true })
                    }
                }
            }

            Column(Modifier.padding(end = 12.dp)) {
                // A watch-only app has nothing to say about this phone; its watch section is the whole card.
                val phonePart = state.status != Status.OtherDevice || watch == null
                if (phonePart) {
                    Text(statusLine(state), style = MaterialTheme.typography.bodyMedium)
                    state.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                when (val busy = if (phonePart) state.busy else null) {
                    is Busy.Downloading -> {
                        LinearProgressIndicator(progress = { busy.fraction }, modifier = Modifier.fillMaxWidth())
                        Caption("Downloading ${(busy.fraction * 100).toInt()}%")
                    }
                    Busy.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Caption("Installing")
                    }
                    Busy.Checking -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Caption("Checking")
                    }
                    null -> when (state.status) {
                        Status.NotInstalled -> Button(onClick = onInstall) { Text("Install") }
                        Status.UpdateAvailable -> Button(onClick = onInstall) { Text("Update") }
                        Status.UpToDate -> if (canOpen) OutlinedButton(onClick = onOpen) { Text("Open") }
                        else -> {}
                    }
                }
                if (watch != null) {
                    if (phonePart) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    WatchSection(watch, onWatchInstall)
                }
            }
        }
    }

    if (choosingTarget) {
        TargetDialog(state.repo.target, onDismiss = { choosingTarget = false }) {
            choosingTarget = false
            onChange(state.repo.copy(target = it))
        }
    }
    if (showNotes) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text(state.release?.title.orEmpty()) },
            text = {
                Text(
                    state.release?.notes?.ifBlank { "This release has no notes." }.orEmpty(),
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showNotes = false }) { Text("Close") } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${state.repo.key}?") },
            text = { Text("Depot stops tracking this repository. The installed app stays on your devices.") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** What the card shows about the watch. [app] is null until the watch has reported this repository. */
private data class WatchRow(
    val app: WatchApp?,
    val link: WatchLinkState,
    val request: WatchRequest?,
    val shareOn: Boolean,
)

@Composable
private fun WatchSection(row: WatchRow, onInstall: () -> Unit) {
    val connected = row.link == WatchLinkState.CONNECTED
    val notConnected = when (row.link) {
        WatchLinkState.CONNECTED -> null
        WatchLinkState.NO_APP ->
            "The connected watch has no Depot, or an older one. Install the newest Depot on the watch."
        WatchLinkState.NO_WATCH -> "No watch connected. Bring the watch in range to install from here."
    }
    Text("Watch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))

    val app = row.app
    if (app == null) {
        Text(
            when {
                notConnected != null -> notConnected
                !row.shareOn -> "Turn on \"Share with watch\" in Settings so the watch knows about this repository."
                else -> "Waiting for the watch"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(watchStatusLine(app), style = MaterialTheme.typography.bodyMedium)
    val busy = app.busy
    // The watch's own progress is newer than anything this phone remembers about its request.
    val error = if (busy != null) null else (row.request as? WatchRequest.Failed)?.message ?: app.error
    error?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(12.dp))
    when {
        busy is Busy.Downloading -> {
            LinearProgressIndicator(progress = { busy.fraction }, modifier = Modifier.fillMaxWidth())
            Caption("Downloading on the watch ${WatchLink.percent(busy)}%")
        }
        // Android decides whether to ask. Until the tap, the install is neither running nor failed.
        app.needsUser -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Caption("Confirm on your watch")
        }
        busy == Busy.Installing -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Caption("Installing on the watch")
        }
        busy == Busy.Checking -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Caption("Checking")
        }
        row.request == WatchRequest.Sending -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Caption("Sending to the watch")
        }
    }
    if (busy == null && row.request != WatchRequest.Sending) {
        val label = when (app.status) {
            Status.NotInstalled -> "Install on watch"
            Status.UpdateAvailable -> "Update on watch"
            else -> null
        }
        if (label != null) {
            // Still offered while a confirmation is open, in case its notification was swiped away.
            if (app.needsUser) Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onInstall, enabled = connected) { Text(label) }
            notConnected?.let { Caption(it) }
        }
    }
}

private fun watchStatusLine(app: WatchApp): String {
    val latest = app.latestTag
    val installed = app.installedVersion
    return when (app.status) {
        Status.Unknown -> "Not checked yet"
        Status.NoRelease -> "This repository has no published release yet"
        Status.NoAsset -> "Release ${latest.orEmpty()} has no APK for the watch"
        Status.OtherDevice -> "Set to install on the phone only"
        Status.NotInstalled -> if (latest != null) "Not installed · latest $latest" else "Not installed"
        Status.UpdateAvailable -> "Installed ${installed.orEmpty()} · ${latest.orEmpty()} available"
        Status.UpToDate -> if (installed != null) "Up to date · $installed" else "Up to date"
    }
}

@Composable
private fun Caption(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun targetName(target: Target) = when (target) {
    Target.AUTO -> "automatic"
    Target.PHONE -> "phone only"
    Target.WATCH -> "watch only"
    Target.BOTH -> "phone and watch"
}

private fun statusLine(state: AppState): String {
    val latest = state.release?.tag
    val installed = state.installedVersion
    return when (state.status) {
        Status.Unknown -> "Not checked yet"
        Status.NoRelease -> "This repository has no published release yet"
        Status.NoAsset -> "Release $latest has no APK for this phone"
        Status.OtherDevice -> "Installed on the watch only"
        Status.NotInstalled -> "Not installed · latest $latest"
        Status.UpdateAvailable -> "Installed $installed · $latest available"
        Status.UpToDate -> "Up to date · $installed"
    }
}

@Composable
private fun TargetDialog(current: Target, onDismiss: () -> Unit, onPick: (Target) -> Unit) {
    val options = listOf(
        Target.AUTO to "Automatic: APKs with \"wear\" or \"watch\" in the file name go to the watch, the rest to the phone",
        Target.PHONE to "Phone only",
        Target.WATCH to "Watch only",
        Target.BOTH to "Phone and watch, matched by file name",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install on") },
        text = {
            Column {
                options.forEach { (target, text) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = target == current, role = Role.RadioButton) { onPick(target) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == current, onClick = null)
                        Text(text, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddRepoDialog(app: DepotApp, onDismiss: () -> Unit) {
    val depot = app.depot
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var mine by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        mine = withContext(Dispatchers.IO) { runCatching { depot.github.repositories() }.getOrDefault(emptyList()) }
    }

    fun add(input: String) {
        val repo = RepoEntry.parse(input)
        when {
            repo == null -> error = "Enter it as owner/repository, or paste the GitHub link."
            !depot.addRepo(repo) -> error = "${repo.key} is already in Depot."
            else -> onDismiss()
        }
    }

    val existing = depot.settings.repos.map { it.key.lowercase() }.toSet()
    val suggestions = mine.filter { it.lowercase() !in existing && it.contains(text.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("owner/repository") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Your repositories", style = MaterialTheme.typography.labelMedium)
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(suggestions, key = { it }) { name ->
                            Text(
                                name,
                                Modifier.fillMaxWidth().clickable { add(name) }.padding(vertical = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { add(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
