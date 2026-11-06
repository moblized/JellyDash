package com.moblized.jellydash.downloads

import android.app.Application
import com.moblized.jellydash.car.FakeJellyfin
import com.moblized.jellydash.data.DownloadQuality
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.MediaSource
import com.moblized.jellydash.jellyfin.PlaybackInfo
import com.moblized.jellydash.jellyfin.PlaybackRequest
import com.moblized.jellydash.jellyfin.SubtitleDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadPlannerTest {
    /** A server with one 1080p movie the phone plays as is, and one 4K HDR movie it doesn't. */
    private class Server : FakeJellyfin() {
        val asked = mutableListOf<PlaybackRequest>()
        override suspend fun playbackInfo(itemId: String, request: PlaybackRequest): PlaybackInfo {
            asked += request
            val fits = itemId != "uhd" || request.maxWidth == null
            val direct = fits && !request.forceServerStream
            return PlaybackInfo(
                "ps",
                listOf(MediaSource("ms-$itemId", "mkv", direct, direct, "/videos/$itemId/master.m3u8?x=1", emptyList(), 1, -1, null, size = 4_200_000_000)),
            )
        }
        override fun directUrl(itemId: String, source: MediaSource, playSessionId: String?) = "direct:$itemId"
        override fun signedUrl(path: String) = "signed:$path"
    }

    private val hd = Item("hd", "Heat", Kind.Movie, runTimeTicks = 2L * 60 * 60 * 10_000_000)
    private val uhd = Item("uhd", "Dune", Kind.Movie, runTimeTicks = 2L * 60 * 60 * 10_000_000)

    @Test
    fun filesThatAlreadyFitAreKeptAsTheyAre() = runBlocking {
        val s = Server()
        val plan = DownloadPlanner.plan(s, hd, DownloadQuality.Truck)
        assertEquals("direct:hd", plan.url)
        assertFalse(plan.converted)
        assertEquals(4_200_000_000, plan.estimatedBytes)
        assertEquals(1, s.asked.size)
        assertEquals(1920, s.asked[0].maxWidth)
        assertEquals(SubtitleDelivery.InFile, s.asked[0].subtitles)
    }

    @Test
    fun bigHdrFilesAreConvertedFor1080p() = runBlocking {
        val s = Server()
        val plan = DownloadPlanner.plan(s, uhd, DownloadQuality.Truck)
        assertTrue(plan.converted)
        assertTrue(plan.isHls)
        assertEquals("signed:/videos/uhd/master.m3u8?x=1", plan.url)
        val second = s.asked[1]
        assertTrue(second.forceServerStream)
        assertEquals(DownloadPlanner.TRUCK_BITRATE, second.maxBitrate)
        assertEquals(SubtitleDelivery.Burned, second.subtitles)
        // Two hours at about 8.2 Mbps: roughly 7.4 GB.
        val gb = plan.estimatedBytes!! / 1_000_000_000.0
        assertTrue("$gb", gb in 7.0..7.8)
    }

    @Test
    fun originalIsAlwaysTheFile() = runBlocking {
        val plan = DownloadPlanner.plan(Server(), uhd, DownloadQuality.Original)
        assertEquals("direct:uhd", plan.url)
        assertFalse(plan.converted)
    }

    @Test
    fun sizes() {
        assertEquals("4.2 GB", DownloadPlanner.size(4_200_000_000))
        assertEquals("820 MB", DownloadPlanner.size(820_000_000))
    }
}
