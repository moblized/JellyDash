package com.moblized.jellydash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Quality
import com.moblized.jellydash.data.Settings
import com.moblized.jellydash.data.ThemeChoice
import com.moblized.jellydash.data.WhenMoving
import com.moblized.jellydash.update.InstallSource
import com.moblized.jellydash.update.Updates

/** Which phone permissions are on. */
data class Perms(
    val location: Boolean,
    val notifications: Boolean,
    /** Installed with the "from Google Play" record Android Auto needs. */
    val autoReady: Boolean = false,
)

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brand.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("▶", color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append("Jelly") }
                withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append("Dash") }
            },
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SignInContent(state: SignInState, onEdit: ((SignInState) -> SignInState) -> Unit, onSignIn: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Wordmark()
        Spacer(Modifier.height(28.dp))
        Text("Your Jellyfin, on the truck's screen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in once here. Then open JellyDash from the app list on Android Auto and watch while you're parked.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Section("Connect to your server") {
            OutlinedTextField(
                value = state.server,
                onValueChange = { v -> onEdit { it.copy(server = v) } },
                label = { Text("Server address") },
                placeholder = { Text("https://jellyfin.example.com") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = { v -> onEdit { it.copy(username = v) } },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> onEdit { it.copy(password = v) } },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSignIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSignIn, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Sign in")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Use the address that works away from home (your HTTPS one), since the truck streams over your phone's data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Check(done: Boolean, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (done) Brand.good else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(Brand.gradient),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, detail: (T) -> String?, onPick: (T) -> Unit) {
    Column {
        for (o in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .selectable(selected = o == selected, role = Role.RadioButton, onClick = { onPick(o) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(label(o), color = MaterialTheme.colorScheme.onSurface)
                    detail(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneHomeContent(
    account: Account,
    settings: Settings,
    perms: Perms,
    update: UpdateState,
    version: String,
    onAllowLocation: () -> Unit,
    onAllowNotifications: () -> Unit,
    onSettings: ((Settings) -> Settings) -> Unit,
    onCheckUpdate: () -> Unit,
    onInstall: (com.moblized.jellydash.update.AppRelease) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Wordmark(Modifier.padding(horizontal = 4.dp))

        Section("Signed in") {
            Text(account.serverName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text("${account.userName} · ${account.server.substringAfter("://")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.padding(start = 0.dp)) { Text("Sign out") }
        }

        Section("Set up the truck") {
            val links = LocalUriHandler.current
            Check(
                done = perms.autoReady,
                title = "Installed for Android Auto",
                body = if (perms.autoReady) {
                    "Android Auto will list JellyDash."
                } else {
                    "Android Auto only runs apps it thinks came from Google Play. Reinstall JellyDash with UnknownAuto, a free tool that does that right on the phone."
                },
                action = if (perms.autoReady) null else "Get UnknownAuto",
                onAction = { links.openUri(InstallSource.UNKNOWN_AUTO_URL) },
            )
            Check(
                done = perms.location,
                title = "Parked check",
                body = if (perms.location) {
                    "On. Video turns off the moment the truck moves."
                } else {
                    "Allow location so JellyDash can tell when you're driving and turn the video off."
                },
                action = if (perms.location) null else "Allow location",
                onAction = onAllowLocation,
            )
            Check(
                done = perms.notifications,
                title = "Playback notice",
                body = "Optional. Shows what's playing while the phone is in your pocket.",
                action = if (perms.notifications) null else "Allow",
                onAction = onAllowNotifications,
            )
            Spacer(Modifier.height(10.dp))
            Text("Show JellyDash on Android Auto", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            if (!perms.autoReady) {
                Step(1, "Install UnknownAuto and Shizuku, then start Shizuku with Wireless debugging. UnknownAuto walks you through it, no computer needed.")
                Step(2, "Download the newest JellyDash APK. In UnknownAuto, tap Load APK…, pick it, and tap Install. It installs over this copy and keeps your sign-in.")
                OutlinedButton(onClick = { links.openUri(Updates.RELEASES_PAGE) }, modifier = Modifier.padding(start = 34.dp)) { Text("Download JellyDash") }
            }
            val n = if (perms.autoReady) 0 else 2
            Step(n + 1, "Open Settings › Connected devices › Android Auto. Tap Version 10 times, then ⋮ › Developer settings, and turn on Unknown sources.")
            Step(n + 2, "Unplug the phone or turn Bluetooth off and on, so the truck reloads its app list. Then pick JellyDash from the app list on the Uconnect screen.")
            Spacer(Modifier.height(6.dp))
            Text(
                "JellyDash shows up with the navigation apps. That's how it gets the whole screen for video. If it's missing, check Customize launcher in Android Auto's settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("When the truck starts moving") {
            Choices(
                options = WhenMoving.entries,
                selected = settings.whenMoving,
                label = { if (it == WhenMoving.KeepAudio) "Hide the picture, keep the sound" else "Pause until I park" },
                detail = { if (it == WhenMoving.KeepAudio) "Like a podcast while you drive" else null },
                onPick = { w -> onSettings { it.copy(whenMoving = w) } },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Either way the video only comes back once you've stopped for 20 seconds and tap \"I'm parked\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Streaming quality") {
            Choices(
                options = Quality.entries,
                selected = settings.quality,
                label = { it.label },
                detail = { it.detail },
                onPick = { q -> onSettings { it.copy(quality = q) } },
            )
        }

        Section("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val all = ThemeChoice.entries
                all.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = settings.theme == t,
                        onClick = { onSettings { it.copy(theme = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, all.size),
                    ) { Text(t.name) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("The app icon switches to match.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Updates") {
            Text("Version $version", color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            when (update) {
                UpdateState.Idle -> OutlinedButton(onClick = onCheckUpdate) { Text("Check for updates") }
                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                UpdateState.UpToDate -> {
                    Text("You have the latest version.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onCheckUpdate) { Text("Check again") }
                }
                is UpdateState.Available -> {
                    Text("Version ${update.release.versionName} is ready.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (update.release.notes.isNotBlank()) {
                        Text(update.release.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { onInstall(update.release) }) { Text("Install update") }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading ${update.release.versionName}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Saved -> {
                    Text("Saved ${update.fileName} to Downloads.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Open UnknownAuto, tap Load APK…, pick it and tap Install. Installing it the usual way would hide JellyDash from Android Auto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is UpdateState.Failed -> {
                    Text(update.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onCheckUpdate) { Text("Try again") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
