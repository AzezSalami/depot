package app.depot.store

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: DepotApp, onBack: () -> Unit) {
    val depot = app.depot
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasToken by remember { mutableStateOf(depot.hasToken) }
    var tokenInput by remember { mutableStateOf("") }
    var tokenStatus by remember { mutableStateOf<String?>(null) }
    var tokenBad by remember { mutableStateOf(false) }
    var autoCheck by remember { mutableStateOf(depot.settings.autoCheck) }
    var autoInstall by remember { mutableStateOf(depot.settings.autoInstall) }
    var syncWatch by remember { mutableStateOf(depot.settings.syncToWatch) }
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var watchStatus by remember { mutableStateOf<String?>(null) }

    suspend fun checkToken() {
        tokenStatus = "Checking…"
        tokenBad = false
        val result = withContext(Dispatchers.IO) { runCatching { depot.github.login() } }
        tokenBad = result.isFailure
        tokenStatus = result.fold({ "Signed in as $it" }, { it.message ?: "Could not reach GitHub" })
    }

    LaunchedEffect(Unit) {
        if (hasToken) launch { checkToken() }
        watches = withContext(Dispatchers.IO) { WatchSync.watches(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Section("GitHub")
            Text(
                "Depot reads releases with a personal access token. Create a fine-grained token, select the " +
                    "repositories you want here, and give it one permission: Contents, read-only.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL))) }) {
                Text("Create a token on GitHub")
            }
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text(if (hasToken) "Replace token" else "Access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = tokenInput.isNotBlank(),
                    onClick = {
                        depot.setToken(tokenInput)
                        tokenInput = ""
                        hasToken = true
                        scope.launch { checkToken() }
                    },
                ) { Text("Save token") }
                if (hasToken) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        depot.setToken(null)
                        hasToken = false
                        tokenStatus = "Token removed from this phone" + if (syncWatch) " and the watch." else "."
                        tokenBad = false
                    }) { Text("Remove token") }
                }
            }
            tokenStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tokenBad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Updates")
            Toggle("Check in the background", "Looks for new releases about every six hours.", autoCheck) {
                autoCheck = it
                depot.setAutoCheck(it)
            }
            Toggle(
                "Install updates automatically",
                "Android 12 and newer can update apps that Depot installed without asking. " +
                    "Otherwise you get a notification to confirm.",
                autoInstall,
            ) {
                autoInstall = it
                depot.setAutoInstall(it)
            }

            Section("Watch")
            Toggle(
                "Share with watch",
                "Sends the token and repository list to Depot on your Wear OS watch, so nothing has to be typed there.",
                syncWatch,
            ) {
                syncWatch = it
                depot.settings.syncToWatch = it
                scope.launch { watchStatus = withContext(Dispatchers.IO) { WatchSync.push(context, depot.settings) } }
            }
            Text(
                when {
                    watches == null -> "Looking for a watch…"
                    watches!!.isEmpty() -> "No watch connected right now."
                    else -> "Connected: ${watches!!.joinToString(", ")}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = syncWatch,
                onClick = {
                    scope.launch {
                        watchStatus = "Sending…"
                        watchStatus = withContext(Dispatchers.IO) { WatchSync.push(context, depot.settings) }
                            ?: "Sent. The watch picks it up when it is in range."
                        watches = withContext(Dispatchers.IO) { WatchSync.watches(context) }
                    }
                },
            ) { Text("Send to watch now") }
            watchStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            }
            Text(
                "Depot $version",
                Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
