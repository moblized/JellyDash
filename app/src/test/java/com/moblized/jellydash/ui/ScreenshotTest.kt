package com.moblized.jellydash.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Quality
import com.moblized.jellydash.data.Settings
import com.moblized.jellydash.data.ThemeChoice
import com.moblized.jellydash.data.WhenMoving
import com.moblized.jellydash.update.AppRelease
import com.moblized.jellydash.Graph
import com.moblized.jellydash.car.FakeJellyfin
import com.moblized.jellydash.data.DownloadQuality
import com.moblized.jellydash.downloads.DownloadEntry
import androidx.media3.exoplayer.offline.Download
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the phone screens, so the look can be checked from CI without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val account = Account("https://jellyfin.moblized.org", "Moblized NAS", "u1", "mo", "tok")

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { JellyTheme(dark) { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
        println("SHOT $name")
    }

    @Composable
    private fun Home(perms: Perms, update: UpdateState = UpdateState.Idle, settings: Settings = Settings()) = PhoneHomeContent(
        account, settings, perms, update, "1.0.12",
        onAllowLocation = {}, onAllowNotifications = {}, onSettings = {}, onCheckUpdate = {}, onInstall = {}, onSignOut = {},
    )

    @Test
    fun library() {
        Graph.reset()
        Graph.override(jellyfin = FakeJellyfin())
        shoot("05_library") {
            LibraryTab(listOf(Page.Libraries), {}, {}, emptyList(), LibraryActions({}, {}))
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    fun showWithDownloads() {
        Graph.reset()
        val fake = FakeJellyfin()
        Graph.override(jellyfin = fake)
        val eps = fake.episodes
        val entries = listOf(
            DownloadEntry(eps[8], Download.STATE_COMPLETED, 100f, 0, 2_100_000_000, DownloadQuality.Truck, true, "a"),
            DownloadEntry(eps[9], Download.STATE_DOWNLOADING, 42f, 900_000_000, 2_100_000_000, DownloadQuality.Truck, true, "b"),
            DownloadEntry(eps[10], Download.STATE_QUEUED, 0f, 0, null, DownloadQuality.Truck, true, "c", waitingForWifi = true),
        )
        shoot("06_show_downloads", dark = true) {
            LibraryTab(listOf(Page.Libraries, Page.Show(fake.show)), {}, {}, entries, LibraryActions({}, {}))
        }
    }

    @Test
    fun downloadsTab() {
        val fake = FakeJellyfin()
        val entries = listOf(
            DownloadEntry(fake.movies[0], Download.STATE_DOWNLOADING, 63f, 2_000_000_000, 3_400_000_000, DownloadQuality.Truck, false, "m1"),
            DownloadEntry(fake.episodes[9], Download.STATE_COMPLETED, 100f, 0, 1_900_000_000, DownloadQuality.Truck, true, "e"),
            DownloadEntry(fake.movies[4], Download.STATE_FAILED, 10f, 0, null, DownloadQuality.Truck, true, "m5"),
        )
        shoot("07_downloads") {
            DownloadsContent(entries, wifiOnly = true, onWifiOnly = {}, onRemove = {}, onRetry = {}, poster = { item, m -> PosterPlaceholder(item, m) })
        }
    }

    @Test
    fun signIn() = shoot("01_sign_in") {
        SignInContent(SignInState(server = "https://jellyfin.moblized.org", username = "mo"), {}, {})
    }

    @Test
    fun signInError() = shoot("02_sign_in_error", dark = true) {
        SignInContent(SignInState(server = "jellyfin.moblized.org", username = "mo", password = "x", error = "Wrong username or password"), {}, {})
    }

    @Test
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    fun home() = shoot("03_home") { Home(Perms(location = false, notifications = false)) }

    @Test
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    fun homeDark() = shoot("04_home_dark", dark = true) {
        Home(
            Perms(location = true, notifications = true),
            UpdateState.Available(AppRelease(14, "1.0.14", "Faster start when you tap Play.", "https://example.com/a.apk", 1)),
            Settings(quality = Quality.High, whenMoving = WhenMoving.Pause, theme = ThemeChoice.Dark),
        )
    }
}
