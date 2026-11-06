package com.moblized.jellydash.car

import com.moblized.jellydash.jellyfin.Browse
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Library
import com.moblized.jellydash.jellyfin.MediaSource
import com.moblized.jellydash.jellyfin.MediaStream
import com.moblized.jellydash.jellyfin.Page
import com.moblized.jellydash.jellyfin.PlaybackInfo
import com.moblized.jellydash.jellyfin.PlaybackReport
import com.moblized.jellydash.jellyfin.PlaybackRequest
import com.moblized.jellydash.jellyfin.ReportKind
import com.moblized.jellydash.jellyfin.ServerInfo
import com.moblized.jellydash.jellyfin.SignIn
import com.moblized.jellydash.jellyfin.Sort
import java.util.concurrent.CopyOnWriteArrayList

/** A pretend server with a small library, answering instantly. */
open class FakeJellyfin : Jellyfin {
    val movies = (1..40).map { i ->
        Item(
            id = "m$i",
            name = "Movie ${'A' + (i % 26)}$i",
            kind = Kind.Movie,
            year = 1990 + i,
            runTimeTicks = 6_000_000_000L * (8 + i % 5),
            positionTicks = if (i == 3) 18_000_000_000L else 0,
            officialRating = "PG-13",
            communityRating = 7.1,
            overview = "A long overview for movie $i. ".repeat(10),
        )
    }
    val show = Item("show1", "Northern Lights", Kind.Series, year = 2021, unplayedCount = 4)
    val seasons = (1..3).map { Item("s$it", "Season $it", Kind.Season, seriesId = "show1", seriesName = "Northern Lights", index = it, childCount = 8) }
    val episodes = seasons.flatMap { s ->
        (1..8).map { e ->
            Item(
                "e${s.index}x$e", "Episode name $e", Kind.Episode,
                seriesName = "Northern Lights", seriesId = "show1", seasonId = s.id, index = e, parentIndex = s.index,
                runTimeTicks = 27_000_000_000L, played = s.index == 1,
            )
        }
    }
    val reports = CopyOnWriteArrayList<Pair<ReportKind, PlaybackReport>>()

    override suspend fun serverInfo(server: String) = ServerInfo("Home NAS", "10.11.2", "srv")
    override suspend fun signIn(server: String, username: String, password: String) = SignIn("tok", "u1", username)
    override suspend fun libraries() = listOf(Library("lib-movies", "Movies", "movies"), Library("lib-shows", "Shows", "tvshows"), Library("lib-home", "Home videos", "homevideos"))
    override suspend fun resume(limit: Int) = listOf(movies[2], episodes[9]).take(limit)
    override suspend fun nextUp(limit: Int) = listOf(episodes[10], episodes[9]).take(limit)
    override suspend fun nextUpFor(seriesId: String): Item? = episodes[10]

    private fun all(where: Browse, sort: Sort): List<Item> {
        val base = when (where.collectionType) {
            "tvshows" -> listOf(show)
            else -> movies
        }
        return if (sort == Sort.Newest) base.reversed() else base.sortedBy { it.name }
    }

    override suspend fun browse(where: Browse, sort: Sort, start: Int, limit: Int): Page {
        val a = all(where, sort)
        return Page(a.drop(start).take(limit), a.size)
    }

    override suspend fun sortNames(where: Browse) = all(where, Sort.Name).map { it.name }
    override suspend fun seasons(seriesId: String) = seasons
    override suspend fun episodes(seriesId: String, seasonId: String?) = if (seasonId == null) episodes else episodes.filter { it.seasonId == seasonId }
    override suspend fun item(id: String) = (movies + episodes + show).first { it.id == id }
    override suspend fun search(term: String, limit: Int) = (movies + show).filter { it.name.contains(term, ignoreCase = true) }.take(limit)

    override suspend fun playbackInfo(itemId: String, request: PlaybackRequest) = PlaybackInfo(
        "ps-$itemId",
        listOf(
            MediaSource(
                "ms-$itemId", "mkv", supportsDirectPlay = !request.forceServerStream, supportsDirectStream = !request.forceServerStream,
                transcodingUrl = "/videos/$itemId/master.m3u8?PlaySessionId=ps-$itemId",
                streams = listOf(
                    MediaStream(1, "Audio", "English - AAC - Stereo", "eng", "aac", false, null, null, true),
                    MediaStream(2, "Audio", "Commentary", "eng", "aac", false, null, null, false),
                    MediaStream(3, "Subtitle", "English", "eng", "subrip", false, "External", "/Videos/$itemId/Subtitles/3/0/Stream.subrip", false),
                    MediaStream(4, "Subtitle", "English (PGS)", "eng", "PGSSUB", false, "Encode", null, false),
                ),
                defaultAudioIndex = 1, defaultSubtitleIndex = -1, runTimeTicks = 54_000_000_000L,
            ),
        ),
    )

    override suspend fun report(kind: ReportKind, report: PlaybackReport) {
        reports += kind to report
    }

    override suspend fun stopEncoding(playSessionId: String) {}
    override suspend fun image(url: String): ByteArray? = null
    override fun imageUrl(item: Item, height: Int): String? = null
    override fun directUrl(itemId: String, source: MediaSource, playSessionId: String?) = "http://127.0.0.1:9/Videos/$itemId/stream?static=true"
    override fun signedUrl(path: String) = "http://127.0.0.1:9$path"
    override fun streamHeaders() = mapOf("Authorization" to "MediaBrowser Token=\"tok\"")
}
