package com.mohamedref.pagemint.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File

/** In-app update control on all screens. Android always asks the user to install. */
@Composable
fun AppUpdaterButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Check for new PageMint Preview updates on GitHub.") }
    var available by remember { mutableStateOf<ReleaseUpdater.Release?>(null) }
    var awaitingPermission by remember { mutableStateOf<File?>(null) }

    fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("PageMint update", uri)
        }
        context.startActivity(intent)
    }

    val installPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = awaitingPermission
        if (file != null && (
            Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
        )) {
            runCatching {
                launchInstaller(file)
                open = false
            }.onFailure { error ->
                message = "Could not open the Android installer: ${error.message.orEmpty()}"
            }
        } else {
            message = "Installation permission was not granted. You can try again from Updates."
        }
        awaitingPermission = null
    }

    fun startInstaller(file: File) {
        if (Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            awaitingPermission = file
            message = "Allow PageMint to install updates in Android settings, then return."
            installPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        } else {
            launchInstaller(file)
            open = false
        }
    }

    fun checkForUpdates() {
        if (busy) return
        open = true
        busy = true
        downloading = false
        progress = 0
        message = "Checking for the latest PageMint Preview…"
        available = null
        scope.launch {
            try {
                when (val result = ReleaseUpdater.check(context)) {
                    is ReleaseUpdater.Check.Available -> {
                        available = result.release
                        message = "Preview ${result.release.version.name} is available."
                    }
                    ReleaseUpdater.Check.Current -> {
                        message = "You're on the latest published PageMint Preview."
                    }
                    ReleaseUpdater.Check.NotPublished -> {
                        message = "No PageMint Preview is published on GitHub yet. " +
                            "The updater will work when the first signed Preview is available."
                    }
                }
            } catch (error: Exception) {
                message = "Could not check updates: ${error.message.orEmpty()}"
            } finally {
                busy = false
            }
        }
    }

    TextButton(onClick = ::checkForUpdates) {
        Text("Preview updates")
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Preview updates") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (busy && !downloading) {
                        CircularProgressIndicator()
                    }
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${progress}% downloaded; verified before installation")
                    }
                    val release = available
                    if (release != null && !downloading) {
                        Text("New Preview: ${release.version.name}",
                            style = MaterialTheme.typography.labelLarge)
                        if (release.notes.isNotBlank()) {
                            Text(release.notes.take(700), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                val release = available
                if (release != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            if (busy) return@TextButton
                            busy = true
                            downloading = true
                            progress = 0
                            message = "Downloading Preview ${release.version.name} from GitHub…"
                            scope.launch {
                                try {
                                    val file = ReleaseUpdater.download(context, release) { percent ->
                                        progress = percent
                                    }
                                    downloading = false
                                    message = "Update verified. Opening Android installer…"
                                    startInstaller(file)
                                } catch (error: ReleaseUpdater.SigningMismatch) {
                                    downloading = false
                                    message = error.message.orEmpty()
                                    available = null
                                } catch (error: Exception) {
                                    downloading = false
                                    message = "Update failed: ${error.message.orEmpty()}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (downloading) "Downloading…" else "Download & install") }
                } else {
                    TextButton(onClick = { open = false }, enabled = !busy) { Text("OK") }
                }
            },
            dismissButton = {
                TextButton(onClick = { open = false }, enabled = !busy) { Text("Later") }
            },
        )
    }
}
