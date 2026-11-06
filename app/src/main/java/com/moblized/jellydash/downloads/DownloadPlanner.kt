package com.moblized.jellydash.downloads

import com.moblized.jellydash.data.DownloadQuality
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.JellyfinException
import com.moblized.jellydash.jellyfin.PlaybackRequest
import com.moblized.jellydash.jellyfin.SubtitleDelivery

/** Where a download comes from and roughly how big it will be. */
data class DownloadPlan(
    val url: String,
    val isHls: Boolean,
    /** True when the server converts it; false when it's the file as is. */
    val converted: Boolean,
    val mediaSourceId: String,
    val estimatedBytes: Long?,
)

/**
 * Decides how to save something:
 * - Best for the truck: the file as is when it's 1080p or smaller and the phone plays it (SDR,
 *   phone-friendly audio). Otherwise the server converts it to 1080p, tone-maps HDR, makes the
 *   sound stereo and draws in your default subtitles.
 * - Original: always the file as is.
 * - Space saver: 720p at about 2.5 Mbps.
 */
object DownloadPlanner {
    private const val NO_LIMIT = 1_000_000_000

    /** Converted downloads for the truck: sharp at 1080p without filling the phone. */
    const val TRUCK_BITRATE = 8_000_000
    const val SAVER_BITRATE = 2_500_000

    suspend fun plan(server: Jellyfin, item: Item, quality: DownloadQuality): DownloadPlan {
        val (width, height, bitrate) = when (quality) {
            DownloadQuality.Truck -> Triple(1920, 1080, TRUCK_BITRATE)
            DownloadQuality.Saver -> Triple(1280, 720, SAVER_BITRATE)
            DownloadQuality.Original -> Triple(null, null, NO_LIMIT)
        }
        // First: can the file be kept as it is?
        val asIs = server.playbackInfo(
            item.id,
            PlaybackRequest(
                maxBitrate = if (quality == DownloadQuality.Saver) SAVER_BITRATE else NO_LIMIT,
                maxWidth = width,
                maxHeight = height,
                subtitles = SubtitleDelivery.InFile,
            ),
        )
        val source = asIs.sources.first()
        if (quality == DownloadQuality.Original || source.supportsDirectPlay || source.supportsDirectStream) {
            return DownloadPlan(
                url = server.directUrl(item.id, source, asIs.playSessionId),
                isHls = false,
                converted = false,
                mediaSourceId = source.id,
                estimatedBytes = source.size,
            )
        }
        // Otherwise the server converts it, once, while it downloads.
        val converted = server.playbackInfo(
            item.id,
            PlaybackRequest(
                maxBitrate = bitrate,
                maxWidth = width,
                maxHeight = height,
                mediaSourceId = source.id,
                forceServerStream = true,
                subtitles = SubtitleDelivery.Burned,
            ),
        )
        val src = converted.sources.first()
        val path = src.transcodingUrl ?: throw JellyfinException("The server couldn't convert this for downloading")
        val runSeconds = (item.runTimeTicks ?: src.runTimeTicks)?.let { it / 10_000_000 }
        return DownloadPlan(
            url = server.signedUrl(path),
            isHls = path.substringBefore('?').lowercase().endsWith(".m3u8"),
            converted = true,
            mediaSourceId = src.id,
            // Video plus about 192 kbps of stereo sound.
            estimatedBytes = runSeconds?.let { (bitrate + 192_000L) * it / 8 },
        )
    }

    /** "1.4 GB", "820 MB" */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${(bytes / 1_000).coerceAtLeast(1)} KB"
    }
}
