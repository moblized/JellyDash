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
