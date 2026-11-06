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

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun permsOf(context: Context) = Perms(
    location = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
    notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
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
            PhoneHomeContent(
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
        prompt?.let { r ->
            AlertDialog(
                onDismissRequest = { model.snooze(r) },
                title = { Text("JellyDash ${r.versionName} is out") },
                text = { Text(r.notes.ifBlank { "A new version is ready to install." }) },
                confirmButton = { TextButton(onClick = { model.install(r) }) { Text("Install") } },
                dismissButton = { TextButton(onClick = { model.snooze(r) }) { Text("Later") } },
            )
        }
    }
}
