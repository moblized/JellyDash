package com.moblized.jellydash.playback

import android.app.Application
import com.moblized.jellydash.jellyfin.MediaSource
import com.moblized.jellydash.jellyfin.MediaStream
import com.moblized.jellydash.jellyfin.PlaybackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamPlannerTest {
    private val server = FakeJellyfinForPlans()

    private fun sub(i: Int, method: String, url: String? = null, codec: String = "subrip") =
        MediaStream(i, "Subtitle", "Sub $i", "eng", codec, false, method, url, false)

    private fun audio(i: Int) = MediaStream(i, "Audio", "Audio $i", "eng", "aac", false, null, null, i == 1)

    private fun source(direct: Boolean, transcode: String? = "/videos/m1/master.m3u8?PlaySessionId=ps", streams: List<MediaStream> = emptyList(), defaultSub: Int? = -1) =
        MediaSource("ms1", "mkv", direct, direct, transcode, streams, 1, defaultSub, null)

    @Test
    fun playsTheFileAsIsWhenThePhoneCan() {
        val plan = StreamPlanner.plan(PlaybackInfo("ps", listOf(source(true))), "m1", server)
        assertEquals("DirectPlay", plan.playMethod)
        assertFalse(plan.isHls)
        assertEquals("direct:m1:ms1:ps", plan.url)
        assertEquals(-1, plan.subtitleIndex)
        assertNull(plan.subtitle)
    }

    @Test
    fun usesTheServersStreamWhenItCant() {
        val plan = StreamPlanner.plan(PlaybackInfo("ps", listOf(source(false))), "m1", server)
        assertEquals("Transcode", plan.playMethod)
        assertTrue(plan.isHls)
        assertEquals("signed:/videos/m1/master.m3u8?PlaySessionId=ps", plan.url)
    }

    @Test
    fun pictureSubtitlesForceTheServerToDrawThem() {
        val s = source(true, streams = listOf(audio(1), sub(4, "Encode", codec = "PGSSUB")))
        val plan = StreamPlanner.plan(PlaybackInfo("ps", listOf(s)), "m1", server, wantedSubtitle = 4)
        assertEquals("Transcode", plan.playMethod)
        assertEquals(4, plan.subtitleIndex)
        assertNull("burned in, nothing to load beside it", plan.subtitle)
    }

    @Test
    fun textSubtitlesLoadBesideTheVideo() {
        val s = source(true, streams = listOf(audio(1), sub(3, "External", "/Videos/m1/ms1/Subtitles/3/0/Stream.vtt")), defaultSub = 3)
        val plan = StreamPlanner.plan(PlaybackInfo("ps", listOf(s)), "m1", server)
        assertEquals("DirectPlay", plan.playMethod)
        assertEquals(3, plan.subtitleIndex)
        assertEquals(StreamPlanner.MIME_VTT, plan.subtitle?.mimeType)
        assertEquals("signed:/Videos/m1/ms1/Subtitles/3/0/Stream.vtt", plan.subtitle?.url)
    }

    @Test
    fun subtitleTypes() {
        assertEquals(StreamPlanner.MIME_SUBRIP, StreamPlanner.mimeFor("/a/Stream.subrip?api_key=1", "subrip"))
        assertEquals(StreamPlanner.MIME_SSA, StreamPlanner.mimeFor("/a/Stream.ass", null))
        assertEquals(StreamPlanner.MIME_SSA, StreamPlanner.mimeFor("/a/Stream", "ssa"))
    }

    @Test
    fun otherAudioTracksComeFromTheServer() {
        val s = source(true, streams = listOf(audio(1), audio(2)))
        assertTrue(StreamPlanner.audioNeedsServer(s, 2))
        assertFalse(StreamPlanner.audioNeedsServer(s, 1))
        assertFalse(StreamPlanner.audioNeedsServer(source(true, streams = listOf(audio(1))), 5))
    }
}
