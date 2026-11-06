package com.moblized.jellydash.jellyfin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Prefs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Real HTTP against a stand-in Jellyfin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinClientTest {
    private lateinit var http: FakeHttpServer
    private val seen = CopyOnWriteArrayList<FakeHttpServer.Request>()
    private var respond: (FakeHttpServer.Request) -> Pair<Int, String> = { 200 to "{}" }
    private lateinit var prefs: Prefs
    private lateinit var client: JellyfinClient

    @Before
    fun setUp() {
        http = FakeHttpServer { r ->
            seen += r
            respond(r)
        }
        prefs = Prefs(ApplicationProvider.getApplicationContext())
        client = JellyfinClient(prefs, version = "1.0.test", deviceName = "Galaxy S24 Ultra")
    }

    @After
    fun tearDown() = http.close()

    private fun signedIn() = prefs.saveAccount(Account(http.address, "Home", "u1", "mo", "tok123"))

    @Test
    fun signInSendsTheRightHeaderAndBody() = runBlocking {
        respond = { r ->
            when (r.path) {
                "/System/Info/Public" -> 200 to """{"ServerName":"Mo's NAS","Version":"10.11.2","Id":"srv"}"""
                "/Users/AuthenticateByName" -> 200 to """{"AccessToken":"tok123","User":{"Id":"u1","Name":"mo"}}"""
                else -> 404 to "{}"
            }
        }
        val info = client.serverInfo(http.address)
        val auth = client.signIn(http.address, "mo", "hunter2")
        assertEquals("Mo's NAS", info.name)
        assertEquals("tok123", auth.token)
        val req = seen.first { it.path == "/Users/AuthenticateByName" }
        assertEquals("POST", req.method)
        val header = req.header("Authorization")!!
        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.contains("Client=\"JellyDash\""))
        assertTrue(header.contains("DeviceId=\"${prefs.deviceId}\""))
        assertTrue(!header.contains("Token="))
        assertEquals("hunter2", JSONObject(req.body).getString("Pw"))
    }

    @Test
    fun wrongPasswordSaysSo() = runBlocking {
        respond = { 401 to "" }
        try {
            client.signIn(http.address, "mo", "nope")
            fail()
        } catch (e: JellyfinException) {
            assertEquals("Wrong username or password", e.message)
        }
    }

    @Test
    fun olderServersGetTheOldPaths() = runBlocking {
        signedIn()
        respond = { r ->
            if (r.path.startsWith("/UserViews")) {
                404 to ""
            } else if (r.path.startsWith("/Users/u1/Views")) {
                200 to """{"Items":[{"Id":"lib","Name":"Movies","CollectionType":"movies"}]}"""
            } else {
                404 to ""
            }
        }
        val libs = client.libraries()
        assertEquals("Movies", libs.single().name)
        assertTrue(seen.last().header("Authorization")!!.contains("Token=\"tok123\""))
    }

    @Test
    fun browseAsksForTheRightThings() = runBlocking {
        signedIn()
        respond = { 200 to """{"Items":[],"TotalRecordCount":0}""" }
        client.browse(Browse("lib", "tvshows"), Sort.Newest, 24, 24)
        val path = seen.last().path
        assertTrue(path, path.startsWith("/Items?"))
        assertTrue(path, path.contains("includeItemTypes=Series"))
        assertTrue(path, path.contains("recursive=true"))
        assertTrue(path, path.contains("sortOrder=Descending"))
        assertTrue(path, path.contains("startIndex=24"))
        assertTrue(path, path.contains("userId=u1"))
    }

    @Test
    fun playbackInfoCarriesTheDeviceProfile() = runBlocking {
        signedIn()
        respond = { 200 to """{"PlaySessionId":"ps","MediaSources":[{"Id":"ms","SupportsDirectPlay":true,"MediaStreams":[]}]}""" }
        val info = client.playbackInfo("m1", PlaybackRequest(maxBitrate = 3_000_000, audioIndex = 2, subtitleIndex = -1, forceServerStream = true))
        assertEquals("ps", info.playSessionId)
        val req = seen.last()
        assertTrue(req.path.startsWith("/Items/m1/PlaybackInfo"))
        val body = JSONObject(req.body)
        assertEquals(3_000_000, body.getInt("MaxStreamingBitrate"))
        assertEquals(2, body.getInt("AudioStreamIndex"))
        assertEquals(false, body.getBoolean("EnableDirectPlay"))
        assertEquals("JellyDash", body.getJSONObject("DeviceProfile").getString("Name"))
    }

    @Test
    fun streamAddressesCarryTheSignIn() {
        signedIn()
        val src = MediaSource("ms", "mkv", true, true, null, emptyList(), null, null, null)
        val url = client.directUrl("m1", src, "ps")
        assertTrue(url, url.startsWith("${http.address}/Videos/m1/stream?static=true"))
        assertTrue(url, url.contains("api_key=tok123"))
        assertEquals("${http.address}/Videos/m1/master.m3u8?x=1&api_key=tok123", client.signedUrl("/Videos/m1/master.m3u8?x=1"))
        // Already signed: left alone.
        assertEquals("${http.address}/a?ApiKey=zz", client.signedUrl("/a?ApiKey=zz"))
    }

    @Test
    fun expiredSignInIsClear() = runBlocking {
        signedIn()
        respond = { 401 to "" }
        try {
            client.resume(5)
            fail()
        } catch (e: JellyfinException) {
            assertTrue(e.signedOut)
        }
    }
}
