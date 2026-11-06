package com.moblized.jellydash.car

import android.app.Application
import android.os.Looper
import androidx.car.app.Screen
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.moblized.jellydash.Graph
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Prefs
import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Sort
import com.moblized.jellydash.safety.MotionGuard
import com.moblized.jellydash.data.DownloadQuality
import com.moblized.jellydash.downloads.DownloadEntry
import com.moblized.jellydash.jellyfin.JellyfinException
import com.moblized.jellydash.jellyfin.Library
import androidx.media3.exoplayer.offline.Download
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Builds every Android Auto screen with a pretend server. The Car App Library checks its own
 * rules (row lengths, button counts, what each template allows) as templates are built, so a
 * screen that breaks them fails here instead of on the truck.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarScreensTest {
    private lateinit var car: TestCarContext
    private lateinit var prefs: Prefs
    private val server = FakeJellyfin()
    private var now = 5_000_000L
    private val guard = MotionGuard { now }

    @Before
    fun setUp() {
        Graph.reset()
        val app = ApplicationProvider.getApplicationContext<Application>()
        prefs = Prefs(app)
        Graph.override(prefs = prefs, jellyfin = server, guard = guard)
        car = TestCarContext.createCarContext(app)
    }

    @After
    fun tearDown() {
        Graph.playback(car).stop()
        Graph.reset()
    }

    private fun signIn() = prefs.saveAccount(Account("http://127.0.0.1:9", "Home NAS", "u1", "mo", "tok"))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Screen> show(screen: T): Pair<T, Template> {
        ScreenController(screen).moveToState(Lifecycle.State.RESUMED)
        idle()
        return screen to screen.onGetTemplate()
    }

    private fun rows(t: ListTemplate): List<Row> =
        (t.singleList?.items ?: t.sectionedLists.flatMap { it.itemList.items }).filterIsInstance<Row>()

    @Test
    fun homeAsksYouToSignInOnThePhone() {
        val (_, t) = show(HomeScreen(car))
        assertTrue(t is MessageTemplate)
        assertEquals("Sign in on your phone", (t as MessageTemplate).title.toString())
    }

    @Test
    fun homeShowsContinueWatchingNextUpAndLibraries() {
        signIn()
        val (_, t) = show(HomeScreen(car))
        assertTrue("got $t", t is ListTemplate)
        val list = t as ListTemplate
        assertFalse(list.isLoading)
        val headers = list.sectionedLists.map { it.header.toString() }
        assertEquals(listOf("Continue watching", "Next up", "Libraries").filter { h -> headers.contains(h) }, headers)
        assertTrue(headers.contains("Continue watching"))
        assertTrue(rows(list).isNotEmpty())
    }

    @Test
    fun bigLibrariesOpenAsGroupsAndGroupsAsPosters() {
        signIn()
        val (_, t) = show(GroupsScreen(car, "Movies", Browse("lib-movies", "movies")))
        val list = t as ListTemplate
        val r = rows(list)
        assertEquals("Recently added", r.first().title.toString())
        assertTrue(r.size > 2)

        val (_, g) = show(GridScreen(car, "Movies · A – C", Browse("lib-movies", "movies"), Sort.Name, 0, 40))
        assertTrue(g is GridTemplate)
        assertTrue((g as GridTemplate).singleList!!.items.isNotEmpty())
    }

    @Test
    fun movieDetails() {
        signIn()
        val (_, t) = show(DetailsScreen(car, server.movies[2]))
        assertTrue(t is PaneTemplate)
        val pane = (t as PaneTemplate).pane
        assertEquals("Resume", pane.actions.first().title.toString())
        assertEquals(2, pane.actions.size)
    }

    @Test
    fun showSeasonsAndEpisodes() {
        signIn()
        val (_, t) = show(SeriesScreen(car, server.show, seasonsAsRows = true))
        val headers = (t as ListTemplate).sectionedLists.map { it.header.toString() }
        assertEquals(listOf("Up next", "Seasons"), headers)

        val (_, flat) = show(SeriesScreen(car, server.show, seasonsAsRows = false))
        assertTrue((flat as ListTemplate).sectionedLists.first().header.toString() == "Up next")

        val (_, eps) = show(EpisodesScreen(car, "show1", server.seasons[1]))
        assertTrue(rows(eps as ListTemplate).first().title.toString().startsWith("1. "))
    }

    @Test
    fun search() {
        signIn()
        val (_, t) = show(SearchScreen(car))
        assertTrue(t is SearchTemplate)
    }

    @Test
    fun playerButtonsFollowTheParkedCheck() {
        signIn()
        val (screen, t) = show(PlayerScreen(car))
        assertTrue(t is NavigationTemplate)
        fun titles(): List<String> = (screen.onGetTemplate() as NavigationTemplate).actionStrip!!.actions.mapNotNull { it.title?.toString() }

        // Nothing confirmed yet: "I'm parked" is offered.
        assertTrue(titles().toString(), titles().contains("I'm parked"))

        guard.confirmParked()
        idle()
        assertFalse(titles().contains("I'm parked"))

        // Driving off hides it; a short stop doesn't bring it back.
        guard.onSpeed(10f, MotionGuard.Source.Car)
        idle()
        assertFalse(titles().contains("I'm parked"))
        now += 21_000
        guard.tick()
        idle()
        assertTrue(titles().contains("I'm parked"))
    }

    @Test
    fun downloadsComeFirstAndStillShowOffline() {
        signIn()
        Graph.downloads(car).setEntriesForTest(
            listOf(DownloadEntry(server.movies[5], Download.STATE_COMPLETED, 100f, 3_000_000_000, 3_000_000_000, DownloadQuality.Truck, false, "ms-m6")),
        )
        val (_, t) = show(HomeScreen(car))
        assertEquals("Downloads", (t as ListTemplate).sectionedLists.first().header.toString())
        assertTrue(rows(t).first().texts.first().toString().startsWith("On your phone"))

        val down = object : FakeJellyfin() {
            override suspend fun libraries(): List<Library> = throw JellyfinException("Can't reach the server")
        }
        Graph.override(jellyfin = down)
        val (_, off) = show(HomeScreen(car))
        assertTrue("got $off", off is ListTemplate)
        assertEquals(listOf("Offline · your downloads"), (off as ListTemplate).sectionedLists.map { it.header.toString() })
    }

    @Test
    fun audioAndSubtitleChoices() {
        signIn()
        Graph.playback(car).play(server.movies[0])
        idle()
        val (_, t) = show(TracksScreen(car))
        val headers = (t as ListTemplate).sectionedLists.map { it.header.toString() }
        assertEquals(listOf("Audio", "Subtitles"), headers)
        val subs = t.sectionedLists[1].itemList.items.filterIsInstance<Row>().map { it.title.toString() }
        assertEquals(listOf("Off", "English", "English (PGS)"), subs)
    }
}
