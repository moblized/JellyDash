package com.moblized.jellydash.jellyfin

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Jellyfin's JSON, as recent servers send it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JellyfinJsonTest {
    private val episode = """
        {"Name":"Pilot","Id":"ep1","Type":"Episode","IsFolder":false,"SeriesName":"Northern Lights","SeriesId":"show1",
         "SeasonId":"s1","IndexNumber":1,"ParentIndexNumber":2,"RunTimeTicks":27000000000,"ImageTags":{},
         "SeriesPrimaryImageTag":"abc","UserData":{"PlaybackPositionTicks":9000000000,"Played":false}}
    """.trimIndent()

    @Test
    fun episodeUsesItsShowsPosterWhenItHasNone() {
        val item = JellyfinJson.item(JSONObject(episode))
        assertEquals(Kind.Episode, item.kind)
        assertEquals("show1", item.imageItemId)
        assertEquals("abc", item.imageTag)
        assertEquals(2, item.parentIndex)
        assertEquals(1, item.index)
        assertEquals(2_700_000L, item.runTimeMs)
        assertEquals(900_000L, item.positionMs)
        assertTrue(item.inProgress)
    }

    @Test
    fun movieWithItsOwnPoster() {
        val item = JellyfinJson.item(
            JSONObject("""{"Name":"Heat","Id":"m1","Type":"Movie","ProductionYear":1995,"OfficialRating":"R","CommunityRating":8.3,"ImageTags":{"Primary":"t1"},"UserData":{"Played":true}}"""),
        )
        assertEquals("m1", item.imageItemId)
        assertEquals(1995, item.year)
        assertTrue(item.played)
        assertFalse(item.inProgress)
    }

    @Test
    fun pageCountsEverythingNotJustThisPage() {
        val page = JellyfinJson.page("""{"Items":[$episode],"TotalRecordCount":212,"StartIndex":0}""")
        assertEquals(1, page.items.size)
        assertEquals(212, page.total)
    }

    @Test
    fun bareArraysParseToo() {
        assertEquals(1, JellyfinJson.itemList("[$episode]").size)
    }

    @Test
    fun librariesKeepOnlyVideo() {
        val libs = JellyfinJson.libraries(
            """{"Items":[{"Id":"a","Name":"Movies","CollectionType":"movies"},{"Id":"b","Name":"Music","CollectionType":"music"},
               {"Id":"c","Name":"Shows","CollectionType":"tvshows"},{"Id":"d","Name":"Home","CollectionType":null}]}""",
        )
        assertEquals(listOf("Movies", "Shows", "Home"), libs.filter { it.isVideo }.map { it.name })
    }

    @Test
    fun playbackInfoWithStreams() {
        val info = JellyfinJson.playbackInfo(
            """{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":true,"SupportsDirectStream":true,
               "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,"MediaStreams":[
               {"Index":0,"Type":"Video","Codec":"hevc"},
               {"Index":1,"Type":"Audio","Codec":"eac3","Language":"eng","DisplayTitle":"English - Dolby Digital+ - 5.1","IsDefault":true,"Channels":6},
               {"Index":2,"Type":"Audio","Codec":"aac","Language":"spa","DisplayTitle":"Spanish - AAC - Stereo"},
               {"Index":3,"Type":"Subtitle","Codec":"subrip","Language":"eng","DisplayTitle":"English","DeliveryMethod":"External","DeliveryUrl":"/Videos/x/ms1/Subtitles/3/0/Stream.subrip","IsExternal":false},
               {"Index":4,"Type":"Subtitle","Codec":"PGSSUB","DisplayTitle":"English (PGS)","DeliveryMethod":"Encode"}]}]}""",
        )
        assertEquals("ps1", info.playSessionId)
        val src = info.sources.single()
        assertEquals(2, src.audio.size)
        assertEquals(2, src.subtitles.size)
        assertEquals("English - Dolby Digital+ - 5.1", src.audio[0].title)
        assertEquals("Encode", src.subtitles[1].deliveryMethod)
        assertEquals(-1, src.defaultSubtitleIndex)
    }

    @Test
    fun playbackInfoErrorsAreReadable() {
        try {
            JellyfinJson.playbackInfo("""{"MediaSources":[],"ErrorCode":"NotAllowed"}""")
            fail()
        } catch (e: JellyfinException) {
            assertEquals("Your Jellyfin account isn't allowed to play this", e.message)
        }
    }

    @Test
    fun reportBody() {
        val o = JSONObject(
            JellyfinJson.report(PlaybackReport("i", "ms", "ps", 12_345, isPaused = true, playMethod = "Transcode", audioIndex = 1, subtitleIndex = -1, event = "Pause")),
        )
        assertEquals("Pause", o.getString("EventName"))
        assertEquals(12_345, o.getLong("PositionTicks"))
        assertEquals(-1, o.getInt("SubtitleStreamIndex"))
        assertTrue(o.getBoolean("IsPaused"))
    }

    @Test
    fun deviceProfileSendsHdrAndSurroundToTheServer() {
        val p = DeviceProfile.build(8_000_000)
        assertEquals(8_000_000, p.getInt("MaxStreamingBitrate"))
        val direct = p.getJSONArray("DirectPlayProfiles").getJSONObject(0)
        assertFalse(direct.getString("AudioCodec").contains("ac3"))
        val codecs = p.getJSONArray("CodecProfiles")
        val hevc = (0 until codecs.length()).map { codecs.getJSONObject(it) }.first { it.optString("Codec") == "hevc" }
        val cond = hevc.getJSONArray("Conditions").getJSONObject(0)
        assertEquals("VideoRangeType", cond.getString("Property"))
        assertEquals("SDR", cond.getString("Value"))
        val transcode = p.getJSONArray("TranscodingProfiles").getJSONObject(0)
        assertEquals("hls", transcode.getString("Protocol"))
        assertEquals("2", transcode.getString("MaxAudioChannels"))
    }

    @Test
    fun ticksAndClocks() {
        assertEquals("1 hr 42 min", Ticks.duration(102 * 60_000L))
        assertEquals("42 min", Ticks.duration(42 * 60_000L))
        assertEquals("2 hr", Ticks.duration(120 * 60_000L))
        assertEquals("1:02:03", Ticks.clock(3_723_000))
        assertEquals("2:03", Ticks.clock(123_000))
        assertNull(JellyfinJson.item(JSONObject("""{"Id":"x","Type":"Movie"}""")).runTimeTicks)
    }
}
