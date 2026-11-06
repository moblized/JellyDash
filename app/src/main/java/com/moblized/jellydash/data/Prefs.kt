package com.moblized.jellydash.data

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Who you're signed in as. */
data class Account(
    val server: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val token: String,
)

enum class ThemeChoice { System, Light, Dark }

/** What happens to a video when the truck starts moving. The picture always goes away. */
enum class WhenMoving { KeepAudio, Pause }

/** Streaming limits, in bits per second. Jellyfin converts anything bigger. */
enum class Quality(val bitsPerSecond: Int, val label: String, val detail: String) {
    Low(3_000_000, "3 Mbps", "Weak signal or a tight data plan"),
    Medium(8_000_000, "8 Mbps", "Good on most LTE and 5G"),
    High(20_000_000, "20 Mbps", "Strong 5G or home Wi-Fi"),
    Original(120_000_000, "Original", "Plays files as they are when it can"),
}

/** How downloads are saved. */
enum class DownloadQuality(val label: String, val detail: String) {
    Truck("Best for the truck", "Up to 1080p, the most a car screen shows. Files that already fit are saved exactly as they are"),
    Original("Original file", "Exactly as it is on your server. Biggest, and 4K HDR or surround-only files may not play right"),
    Saver("Space saver", "720p, about 2 GB for a two-hour movie"),
}

data class Settings(
    val quality: Quality = Quality.Medium,
    val whenMoving: WhenMoving = WhenMoving.KeepAudio,
    val theme: ThemeChoice = ThemeChoice.System,
    val downloadQuality: DownloadQuality = DownloadQuality.Truck,
    val wifiOnly: Boolean = true,
)

/** Where you got to in something, kept on the phone so downloads resume offline. */
data class LocalProgress(val positionMs: Long, val played: Boolean, val synced: Boolean)

/**
 * Everything the app remembers. The sign-in lives in its own file so it can be left out of
 * cloud backups.
 */
open class Prefs(context: Context) {
    private val account = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _account = MutableStateFlow(readAccount())
    val accountFlow: StateFlow<Account?> = _account.asStateFlow()
    open val currentAccount: Account? get() = _account.value

    private val _settings = MutableStateFlow(readSettings())
    val settingsFlow: StateFlow<Settings> = _settings.asStateFlow()
    open val currentSettings: Settings get() = _settings.value

    /** Identifies this phone to Jellyfin, so the dashboard shows it as one device. */
    val deviceId: String
        get() = settings.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            settings.edit().putString("device_id", it).apply()
        }

    private fun readAccount(): Account? {
        val server = account.getString("server", null) ?: return null
        val token = account.getString("token", null) ?: return null
        return Account(
            server = server,
            serverName = account.getString("server_name", null) ?: server,
            userId = account.getString("user_id", null) ?: return null,
            userName = account.getString("user_name", null) ?: "",
            token = token,
        )
    }

    fun saveAccount(a: Account) {
        account.edit()
            .putString("server", a.server)
            .putString("server_name", a.serverName)
            .putString("user_id", a.userId)
            .putString("user_name", a.userName)
            .putString("token", a.token)
            .apply()
        _account.value = a
    }

    fun signOut() {
        account.edit().clear().apply()
        _account.value = null
    }

    /** The server address typed last, kept after signing out so it's there next time. */
    var lastServer: String
        get() = settings.getString("last_server", "") ?: ""
        set(v) = settings.edit().putString("last_server", v).apply()

    private fun readSettings() = Settings(
        quality = enumOr(settings.getString("quality", null), Quality.Medium),
        whenMoving = enumOr(settings.getString("when_moving", null), WhenMoving.KeepAudio),
        theme = enumOr(settings.getString("theme", null), ThemeChoice.System),
        downloadQuality = enumOr(settings.getString("download_quality", null), DownloadQuality.Truck),
        wifiOnly = settings.getBoolean("wifi_only", true),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        settings.edit()
            .putString("quality", s.quality.name)
            .putString("when_moving", s.whenMoving.name)
            .putString("theme", s.theme.name)
            .putString("download_quality", s.downloadQuality.name)
            .putBoolean("wifi_only", s.wifiOnly)
            .apply()
        _settings.value = s
    }

    fun isDark(context: Context): Boolean = when (currentSettings.theme) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.System ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private val progress = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun localProgress(itemId: String): LocalProgress? {
        val raw = progress.getString(itemId, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        return LocalProgress(parts[0].toLongOrNull() ?: return null, parts[1] == "1", parts[2] == "1")
    }

    fun saveProgress(itemId: String, p: LocalProgress) {
        progress.edit().putString(itemId, "${p.positionMs}|${if (p.played) 1 else 0}|${if (p.synced) 1 else 0}").apply()
    }

    /** Progress not yet sent to the server, because there was no connection at the time. */
    fun unsyncedProgress(): Map<String, LocalProgress> =
        progress.all.keys.mapNotNull { id -> localProgress(id)?.takeIf { !it.synced }?.let { id to it } }.toMap()

    var lastUpdateCheck: Long
        get() = settings.getLong("last_update_check", 0)
        set(v) = settings.edit().putLong("last_update_check", v).apply()

    var snoozedUpdate: Int
        get() = settings.getInt("snoozed_update", 0)
        set(v) = settings.edit().putInt("snoozed_update", v).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
