package com.moblized.jellydash.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.IconSwitcher
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Settings
import com.moblized.jellydash.update.AppRelease
import com.moblized.jellydash.update.InstallSource
import com.moblized.jellydash.update.Updater
import com.moblized.jellydash.update.Updates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignInState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** Saved to Downloads for UnknownAuto, so Android Auto keeps showing the app. */
    data class Saved(val release: AppRelease, val fileName: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** The phone side: signing in, settings and updates. */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Graph.prefs(app)
    private val jellyfin = Graph.jellyfin(app)
    private val updater = Updater(app)

    val account: StateFlow<Account?> = prefs.accountFlow
    val settings: StateFlow<Settings> = prefs.settingsFlow

    private val _signIn = MutableStateFlow(SignInState(server = prefs.lastServer))
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** Shown once per new version when the app opens. */
    private val _prompt = MutableStateFlow<AppRelease?>(null)
    val prompt: StateFlow<AppRelease?> = _prompt.asStateFlow()

    val versionName: String get() = updater.installedVersionName

    fun edit(change: (SignInState) -> SignInState) = _signIn.update { change(it).copy(error = null) }

    fun signIn() {
        val s = _signIn.value
        if (s.busy) return
        val server = ServerAddress.normalize(s.server)
        if (server == null) {
            _signIn.update { it.copy(error = "Enter your server's address, like https://jellyfin.example.com") }
            return
        }
        if (s.username.isBlank()) {
            _signIn.update { it.copy(error = "Enter your Jellyfin username") }
            return
        }
        _signIn.update { it.copy(busy = true, error = null, server = server) }
        viewModelScope.launch {
            try {
                val info = jellyfin.serverInfo(server)
                val auth = jellyfin.signIn(server, s.username.trim(), s.password)
                prefs.lastServer = server
                prefs.saveAccount(Account(server, info.name, auth.userId, auth.userName.ifBlank { s.username.trim() }, auth.token))
                _signIn.value = SignInState(server = server)
            } catch (e: Exception) {
                _signIn.update { it.copy(busy = false, error = e.message ?: "Couldn't sign in") }
            }
        }
    }

    fun signOut() {
        Graph.playback(getApplication<Application>()).stop()
        prefs.signOut()
        _signIn.value = SignInState(server = prefs.lastServer)
    }

    fun changeSettings(change: (Settings) -> Settings) {
        prefs.update(change)
        IconSwitcher.apply(getApplication<Application>(), prefs.isDark(getApplication<Application>()))
    }

    /** Looks for a new build at most every few hours on launch; always when asked. */
    fun checkForUpdate(manual: Boolean) {
        if (!manual && System.currentTimeMillis() - prefs.lastUpdateCheck < Updates.CHECK_EVERY_MS) return
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val latest = updater.fetchLatest()
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (latest != null && latest.versionCode > updater.installedVersionCode && latest.apkUrl != null) {
                    _update.value = UpdateState.Available(latest)
                    if (!manual && prefs.snoozedUpdate != latest.versionCode) _prompt.value = latest
                } else {
                    _update.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                }
            } catch (e: Exception) {
                _update.value = if (manual) UpdateState.Failed(e.message ?: "Couldn't reach GitHub") else UpdateState.Idle
            }
        }
    }

    fun snooze(release: AppRelease) {
        prefs.snoozedUpdate = release.versionCode
        _prompt.value = null
    }

    fun install(release: AppRelease) {
        _prompt.value = null
        val app = getApplication<Application>()
        if (InstallSource.readyForAndroidAuto(app)) {
            // A normal install would lose the "from Google Play" record and hide JellyDash in the car.
            _update.value = UpdateState.Downloading(release, 0f)
            viewModelScope.launch {
                try {
                    val file = updater.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }
                    val name = InstallSource.saveToDownloads(app, file)
                    _update.value = UpdateState.Saved(release, name)
                } catch (e: Exception) {
                    _update.value = UpdateState.Failed(e.message ?: "Download failed")
                }
            }
            return
        }
        if (!updater.canInstall()) {
            updater.openInstallPermission()
            _update.value = UpdateState.Available(release)
            return
        }
        _update.value = UpdateState.Downloading(release, 0f)
        viewModelScope.launch {
            try {
                val file = updater.download(release) { p -> _update.value = UpdateState.Downloading(release, p) }
                _update.value = UpdateState.Available(release)
                updater.install(file)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }
}

object ServerAddress {
    /** "jellyfin.example.com" → "https://jellyfin.example.com"; keeps http:// for local addresses typed that way. */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty() || ' ' in s) return null
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
        val host = s.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isEmpty() || ('.' !in host && host != "localhost" && !host.all { it.isLetterOrDigit() || it == '-' })) return null
        // A pasted web-app address ends in /web/…; the API lives one level up.
        val schemeEnd = s.indexOf("://") + 3
        val web = s.indexOf("/web", startIndex = schemeEnd, ignoreCase = true)
        if (web > 0 && (web + 4 == s.length || s[web + 4] in "/#?")) s = s.substring(0, web)
        return s.trimEnd('/')
    }
}
