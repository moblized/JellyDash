package com.moblized.jellydash.jellyfin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tells Jellyfin what this phone can play as is. Anything else, the server converts.
 *
 * - Video: H.264, HEVC, VP9 and AV1 in MP4, MKV or WebM play directly.
 * - HDR is sent to the server to tone-map, because a car screen is SDR and would show HDR washed out.
 * - Audio: AAC, MP3, Opus, FLAC and Vorbis play directly. Surround formats like AC3, DTS and TrueHD
 *   are converted to stereo AAC, which is what a car stereo gets anyway.
 * - Text subtitles come as separate files; picture subtitles (PGS, DVD) are burned in by the server.
 */
object DeviceProfile {
    private val directVideo = listOf("h264", "hevc", "vp9", "av1")
    private val directAudio = "aac,mp3,opus,flac,vorbis"

    fun build(maxBitrate: Int): JSONObject = JSONObject().apply {
        put("Name", "JellyDash")
        put("MaxStreamingBitrate", maxBitrate)
        put("MaxStaticBitrate", maxBitrate)
        put("MusicStreamingTranscodingBitrate", 320_000)
        put(
            "DirectPlayProfiles",
            JSONArray().put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Container", "mp4,m4v,mkv,webm,mov")
                    .put("VideoCodec", directVideo.joinToString(","))
                    .put("AudioCodec", directAudio),
            ),
        )
        put(
            "TranscodingProfiles",
            JSONArray().put(
                JSONObject()
                    .put("Type", "Video")
                    .put("Container", "ts")
                    .put("Protocol", "hls")
                    .put("Context", "Streaming")
                    .put("VideoCodec", "h264")
                    .put("AudioCodec", "aac")
                    .put("MaxAudioChannels", "2")
                    .put("MinSegments", 1)
                    .put("BreakOnNonKeyFrames", true),
            ),
        )
        put("ContainerProfiles", JSONArray())
        val codecs = JSONArray()
        for (codec in directVideo) {
            val conditions = JSONArray().put(condition("EqualsAny", "VideoRangeType", "SDR"))
            if (codec == "h264") conditions.put(condition("LessThanEqual", "VideoBitDepth", "8"))
            codecs.put(JSONObject().put("Type", "Video").put("Codec", codec).put("Conditions", conditions))
        }
        codecs.put(
            JSONObject().put("Type", "VideoAudio").put("Conditions", JSONArray().put(condition("LessThanEqual", "AudioChannels", "2"))),
        )
        put("CodecProfiles", codecs)
        val subs = JSONArray()
        for (f in listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")) subs.put(JSONObject().put("Format", f).put("Method", "External"))
        for (f in listOf("pgs", "pgssub", "dvdsub", "dvbsub", "sub", "idx")) subs.put(JSONObject().put("Format", f).put("Method", "Encode"))
        put("SubtitleProfiles", subs)
    }

    private fun condition(c: String, property: String, value: String) = JSONObject()
        .put("Condition", c)
        .put("Property", property)
        .put("Value", value)
        .put("IsRequired", false)
}
