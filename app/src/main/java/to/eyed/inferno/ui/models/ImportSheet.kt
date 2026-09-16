package to.eyed.inferno.ui.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import to.eyed.inferno.ui.S
import to.eyed.inferno.ui.components.FormField
import to.eyed.inferno.ui.components.InfernoSheet
import to.eyed.inferno.ui.components.PrimaryButton
import to.eyed.inferno.ui.components.SheetHeader
import to.eyed.inferno.ui.theme.Ink
import to.eyed.inferno.ui.theme.Typography
import to.eyed.inferno.vm.AppViewModel

// Import GGUF via SAF: pick the text model, confirm a display name (empty = the
// GGUF header's name), import. A projector is attached afterwards from the imported row's menu, so the
// repository can pair it with the import id it minted.

/** Launches the document picker for a text model and hosts the confirm sheet. Returns the "pick" action. */
@Composable
fun rememberImportFlow(appVm: AppViewModel): () -> Unit {
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingUri = uri }
    pendingUri?.let { uri ->
        ImportConfirmSheet(
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf",
            onDismiss = { pendingUri = null },
            onImport = { name -> appVm.importModel(uri, name.ifBlank { null }, isMmproj = false, pairWith = null); pendingUri = null },
        )
    }
    return { picker.launch(arrayOf("*/*")) }
}

/** Picks a projector file for an imported model; the repository verifies it is a `clip` GGUF. */
@Composable
fun rememberProjectorPicker(appVm: AppViewModel, pairWith: String): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) appVm.importModel(uri, null, isMmproj = true, pairWith = pairWith)
    }
    return { picker.launch(arrayOf("*/*")) }
}

@Composable
private fun ImportConfirmSheet(fileName: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    InfernoSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding()) {
            SheetHeader(S.importTitle, Lucide.X, onLeading = onDismiss)
            Text(fileName, style = Typography.bodyMedium, color = Ink.I300)
            Spacer(Modifier.height(14.dp))
            FormField(S.importName, name, { name = it }, placeholder = S.importNamePlaceholder)
            Spacer(Modifier.height(10.dp))
            Text(S.importHint, style = Typography.bodySmall, color = Ink.I500)
            Spacer(Modifier.height(18.dp))
            PrimaryButton(S.importGguf, onClick = { onImport(name.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** True while a validated network is up; drives the offline notice. */
@Composable
fun rememberIsOnline(): State<Boolean> {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    val online = remember { mutableStateOf(cm.isValidated()) }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { online.value = cm.isValidated() }
            override fun onLost(network: Network) { online.value = cm.isValidated() }
            override fun onAvailable(network: Network) { online.value = cm.isValidated() }
        }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

private fun ConnectivityManager.isValidated(): Boolean {
    val caps = getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Opens the app's notification settings page (the "Enable notifications" notice). */
fun openNotificationSettings(context: Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
