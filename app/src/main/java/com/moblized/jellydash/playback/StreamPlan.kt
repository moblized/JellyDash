package com.moblized.jellydash.playback

import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.JellyfinException
import com.moblized.jellydash.jellyfin.MediaSource
import com.moblized.jellydash.jellyfin.MediaStream
import com.moblized.jellydash.jellyfin.PlaybackInfo

/** A subtitle file to load beside the video. */
data class SideSubtitle(val url: String, val mimeType: String, val language: String?, val label: String)

/** How one video will be played: from which address, and with which tracks. */
data class StreamPlan(
    val url: String,
    val isHls: Boolean,
    /** "DirectPlay" or "Transcode", as Jellyfin's dashboard shows it */
    val playMethod: String,
    val source: MediaSource,
    val playSessionId: String?,
    val audioIndex: Int?,
    /** -1 when subtitles are off */
    val subtitleIndex: Int,
    val subtitle: SideSubtitle?,
)

object StreamPlanner {
    const val MIME_SUBRIP = "application/x-subrip"
    const val MIME_VTT = "text/vtt"
    const val MIME_SSA = "text/x-ssa"

    /**
     * Picks the stream from the server's answer. A file plays as is when the server says the phone can
     * handle it; otherwise the server's converted stream is used.
     */
    fun plan(
        info: PlaybackInfo,
        itemId: String,
        server: Jellyfin,
        wantedAudio: Int? = null,
        wantedSubtitle: Int? = null,
    ): StreamPlan {
        val source = info.sources.first()
        val audio = wantedAudio ?: source.defaultAudioIndex
        val subIndex = wantedSubtitle ?: source.defaultSubtitleIndex ?: -1
        val sub = source.subtitles.firstOrNull { it.index == subIndex }
        val burnIn = sub?.deliveryMethod == "Encode"
        val canDirect = (source.supportsDirectPlay || source.supportsDirectStream) && !burnIn
        val transcode = source.transcodingUrl
        val (url, hls, method) = when {
            canDirect -> Triple(server.directUrl(itemId, source, info.playSessionId), false, "DirectPlay")
            transcode != null -> Triple(server.signedUrl(transcode), isHls(transcode), "Transcode")
            else -> throw JellyfinException("The server couldn't make a stream this phone can play")
        }
        return StreamPlan(
            url = url,
            isHls = hls,
            playMethod = method,
            source = source,
            playSessionId = info.playSessionId,
            audioIndex = audio,
            subtitleIndex = if (sub == null) -1 else subIndex,
            subtitle = sub?.takeIf { !burnIn }?.let { side(it, server) },
        )
    }

    private fun isHls(url: String) = url.substringBefore('?').lowercase().endsWith(".m3u8")

    /** Text subtitles the server sends as their own file. */
    private fun side(s: MediaStream, server: Jellyfin): SideSubtitle? {
        if (s.deliveryMethod != "External") return null
        val path = s.deliveryUrl ?: return null
        return SideSubtitle(server.signedUrl(path), mimeFor(path, s.codec), s.language, s.title)
    }

    fun mimeFor(url: String, codec: String?): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext.ifEmpty { codec?.lowercase() }) {
            "vtt", "webvtt" -> MIME_VTT
            "ass", "ssa" -> MIME_SSA
            "srt", "subrip" -> MIME_SUBRIP
            else -> when (codec?.lowercase()) {
                "ass", "ssa" -> MIME_SSA
                "vtt", "webvtt" -> MIME_VTT
                else -> MIME_SUBRIP
            }
        }
    }

    /** True when switching to this audio track needs the server to send its own stream. */
    fun audioNeedsServer(source: MediaSource, wanted: Int?): Boolean =
        wanted != null && wanted != source.defaultAudioIndex && source.audio.size > 1
}
