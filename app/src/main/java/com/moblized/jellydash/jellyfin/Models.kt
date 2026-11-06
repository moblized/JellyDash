package com.moblized.jellydash.jellyfin

/** What kind of thing an item is, as far as browsing and playing go. */
enum class Kind {
    Movie, Series, Season, Episode, Video, MusicVideo, BoxSet, Folder, Other;

    val playable: Boolean get() = this == Movie || this == Episode || this == Video || this == MusicVideo

    companion object {
        fun of(type: String?, isFolder: Boolean): Kind = when (type) {
            "Movie" -> Movie
            "Series" -> Series
            "Season" -> Season
            "Episode" -> Episode
            "Video" -> Video
            "MusicVideo" -> MusicVideo
            "BoxSet" -> BoxSet
            "Folder", "CollectionFolder", "UserView" -> Folder
            else -> if (isFolder) Folder else Other
        }
    }
}

data class Item(
    val id: String,
    val name: String,
    val kind: Kind,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    /** Episode number, or season number for a season. */
    val index: Int? = null,
    /** Season number for an episode. */
    val parentIndex: Int? = null,
    val year: Int? = null,
    val overview: String? = null,
    val runTimeTicks: Long? = null,
    val positionTicks: Long = 0,
    val played: Boolean = false,
    val unplayedCount: Int? = null,
    val childCount: Int? = null,
    val officialRating: String? = null,
    val communityRating: Double? = null,
    /** The item whose poster to show: itself, or its show for an episode without its own. */
    val imageItemId: String? = null,
    val imageTag: String? = null,
) {
    val runTimeMs: Long? get() = runTimeTicks?.let { Ticks.toMs(it) }
    val positionMs: Long get() = Ticks.toMs(positionTicks)
    val inProgress: Boolean get() = positionTicks > 0 && !played
}

data class Library(val id: String, val name: String, val collectionType: String?) {
    /** Libraries with video in them. Music, books and photos stay out of the car. */
    val isVideo: Boolean
        get() = collectionType == null || collectionType in setOf("movies", "tvshows", "homevideos", "boxsets", "musicvideos", "mixed", "folders")
}

data class Page(val items: List<Item>, val total: Int)

data class ServerInfo(val name: String, val version: String, val id: String)

data class SignIn(val token: String, val userId: String, val userName: String)

data class MediaStream(
    val index: Int,
    /** "Video", "Audio" or "Subtitle" */
    val type: String,
    val title: String,
    val language: String?,
    val codec: String?,
    val isExternal: Boolean,
    /** For subtitles: "External", "Encode", "Embed" or "Hls" */
    val deliveryMethod: String?,
    val deliveryUrl: String?,
    val isDefault: Boolean,
    val channels: Int? = null,
)

data class MediaSource(
    val id: String,
    val container: String?,
    val supportsDirectPlay: Boolean,
    val supportsDirectStream: Boolean,
    val transcodingUrl: String?,
    val streams: List<MediaStream>,
    val defaultAudioIndex: Int?,
    val defaultSubtitleIndex: Int?,
    val runTimeTicks: Long?,
    /** The file's size in bytes, when the server knows it. */
    val size: Long? = null,
) {
    val audio: List<MediaStream> get() = streams.filter { it.type == "Audio" }
    val subtitles: List<MediaStream> get() = streams.filter { it.type == "Subtitle" }
}

data class PlaybackInfo(val playSessionId: String?, val sources: List<MediaSource>)

/** How subtitles should reach the phone. */
enum class SubtitleDelivery {
    /** Text as a separate file, pictures drawn into the video (streaming). */
    Separate,

    /** Left inside the file (downloads kept as they are). */
    InFile,

    /** Drawn into the video by the server (converted downloads). */
    Burned,
}

/** What to ask the server for when starting or changing a stream. */
data class PlaybackRequest(
    val maxBitrate: Int,
    val audioIndex: Int? = null,
    /** -1 turns subtitles off. */
    val subtitleIndex: Int? = null,
    val mediaSourceId: String? = null,
    /** Make the server send its own stream, for a different audio track. */
    val forceServerStream: Boolean = false,
    /** Largest picture the server should send; bigger files get scaled down. */
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val subtitles: SubtitleDelivery = SubtitleDelivery.Separate,
)

/** One playback report to the server, so resume points and "watched" stay in sync. */
data class PlaybackReport(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val positionTicks: Long,
    val isPaused: Boolean,
    val playMethod: String,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val event: String? = null,
)

object Ticks {
    /** Jellyfin counts time in 100-nanosecond ticks. */
    const val PER_MS = 10_000L
    fun toMs(ticks: Long): Long = ticks / PER_MS
    fun fromMs(ms: Long): Long = ms * PER_MS

    /** "1 hr 42 min", "42 min", "under a minute" */
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000).toInt()
        if (minutes < 1) return "under a minute"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h hr"
            else -> "$h hr $m min"
        }
    }

    /** "1:02:03" or "2:03", for the clock under the video. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
