package com.moblized.jellydash.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.moblized.jellydash.R
import com.moblized.jellydash.update.InstallSource

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun permsOf(context: Context) = Perms(
    location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
    notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
    autoReady = InstallSource.readyForAndroidAuto(context),
)

@Composable
fun AppRoot(model: AppModel) {
    val context = LocalContext.current
    val account by model.account.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val signIn by model.signIn.collectAsStateWithLifecycle()
    val update by model.update.collectAsStateWithLifecycle()
    val prompt by model.prompt.collectAsStateWithLifecycle()
    val dark = when (settings.theme) {
        com.moblized.jellydash.data.ThemeChoice.Dark -> true
        com.moblized.jellydash.data.ThemeChoice.Light -> false
        com.moblized.jellydash.data.ThemeChoice.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    // Re-read permissions when coming back from Android's settings.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val perms = remember(tick) { permsOf(context) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    LaunchedEffect(Unit) { model.checkForUpdate(manual = false) }

    JellyTheme(dark) {
        val a = account
        if (a == null) {
            SignInContent(signIn, model::edit, model::signIn)
        } else {
            val downloads by model.downloads.collectAsStateWithLifecycle()
            val library by model.library.collectAsStateWithLifecycle()
            val note by model.note.collectAsStateWithLifecycle()
            var tab by rememberSaveable { mutableIntStateOf(if (perms.autoReady && perms.location) 0 else 2) }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(note) {
                note?.let {
                    snackbar.showSnackbar(it)
                    model.clearNote()
                }
            }
            val actions = remember(model) { LibraryActions(download = model::download, remove = model::removeDownload) }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(painterResource(R.drawable.ic_movie), contentDescription = null) },
                            label = { Text("Library") },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                            label = { Text("Downloads") },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Setup") },
                        )
                    }
                },
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ) { pad ->
                Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                    when (tab) {
                        0 -> LibraryTab(library, model::open, model::back, downloads, actions)
                        1 -> DownloadsContent(
                            entries = downloads,
                            wifiOnly = settings.wifiOnly,
                            onWifiOnly = { on -> model.changeSettings { it.copy(wifiOnly = on) } },
                            onRemove = model::removeDownload,
                            onRetry = { model.download(listOf(it)) },
                            poster = { item, modifier -> Poster(item, modifier, 300) },
                        )
                        else -> PhoneHomeContent(
                            account = a,
                            settings = settings,
                            perms = perms,
                            update = update,
                            version = model.versionName,
                            onAllowLocation = {
                                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            },
                            onAllowNotifications = { if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            onSettings = model::changeSettings,
                            onCheckUpdate = { model.checkForUpdate(manual = true) },
                            onInstall = model::install,
                            onSignOut = model::signOut,
                        )
                    }
                }
            }
        }
        prompt?.let { r ->
            AlertDialog(
                onDismissRequest = { model.snooze(r) },
                title = { Text("JellyDash ${r.versionName} is out") },
                text = {
                    val notes = r.notes.ifBlank { "A new version is ready." }
                    Text(if (perms.autoReady) "$notes\n\nIt downloads to your Downloads folder. Install it with UnknownAuto so Android Auto keeps showing JellyDash." else notes)
                },
                confirmButton = { TextButton(onClick = { model.install(r) }) { Text(if (perms.autoReady) "Download" else "Install") } },
                dismissButton = { TextButton(onClick = { model.snooze(r) }) { Text("Later") } },
            )
        }
    }
}
