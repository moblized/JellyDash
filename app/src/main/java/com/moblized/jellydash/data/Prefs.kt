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

data class Settings(
    val quality: Quality = Quality.Medium,
    val whenMoving: WhenMoving = WhenMoving.KeepAudio,
    val theme: ThemeChoice = ThemeChoice.System,
)

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
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        settings.edit()
            .putString("quality", s.quality.name)
            .putString("when_moving", s.whenMoving.name)
            .putString("theme", s.theme.name)
            .apply()
        _settings.value = s
    }

    fun isDark(context: Context): Boolean = when (currentSettings.theme) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.System ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    var lastUpdateCheck: Long
        get() = settings.getLong("last_update_check", 0)
        set(v) = settings.edit().putLong("last_update_check", v).apply()

    var snoozedUpdate: Int
        get() = settings.getInt("snoozed_update", 0)
        set(v) = settings.edit().putInt("snoozed_update", v).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
