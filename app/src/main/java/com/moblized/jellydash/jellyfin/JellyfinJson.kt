package com.moblized.jellydash.jellyfin

import org.json.JSONArray
import org.json.JSONObject

/** Reads Jellyfin's JSON. Kept apart from the network code so it can be tested on its own. */
object JellyfinJson {
    private fun JSONObject.str(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.double(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null

    fun serverInfo(json: String): ServerInfo {
        val o = JSONObject(json)
        return ServerInfo(
            name = o.str("ServerName") ?: "Jellyfin",
            version = o.str("Version") ?: "",
            id = o.str("Id") ?: "",
        )
    }

    fun signIn(json: String): SignIn {
        val o = JSONObject(json)
        val user = o.optJSONObject("User") ?: JSONObject()
        return SignIn(
            token = o.str("AccessToken") ?: throw JellyfinException("The server didn't send a sign-in token"),
            userId = user.str("Id") ?: throw JellyfinException("The server didn't say who you are"),
            userName = user.str("Name") ?: "",
        )
    }

    fun item(o: JSONObject): Item {
        val id = o.optString("Id")
        val user = o.optJSONObject("UserData")
        val tags = o.optJSONObject("ImageTags")
        val ownTag = tags?.str("Primary")
        val seriesId = o.str("SeriesId")
        val seriesTag = o.str("SeriesPrimaryImageTag")
        val (imageId, imageTag) = when {
            ownTag != null -> id to ownTag
            seriesId != null && seriesTag != null -> seriesId to seriesTag
            else -> null to null
        }
        return Item(
            id = id,
            name = o.str("Name") ?: "Untitled",
            kind = Kind.of(o.str("Type"), o.optBoolean("IsFolder")),
            seriesName = o.str("SeriesName"),
            seriesId = seriesId,
            seasonId = o.str("SeasonId"),
            index = o.int("IndexNumber"),
            parentIndex = o.int("ParentIndexNumber"),
            year = o.int("ProductionYear"),
            overview = o.str("Overview"),
            runTimeTicks = o.long("RunTimeTicks"),
            positionTicks = user?.long("PlaybackPositionTicks") ?: 0L,
            played = user?.optBoolean("Played") ?: false,
            unplayedCount = user?.int("UnplayedItemCount"),
            childCount = o.int("ChildCount"),
            officialRating = o.str("OfficialRating"),
            communityRating = o.double("CommunityRating"),
            imageItemId = imageId,
            imageTag = imageTag,
        )
    }

    fun itemList(json: String): List<Item> {
        val t = json.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("Items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::item) }
    }

    fun page(json: String): Page {
        val o = JSONObject(json)
        val items = itemList(json)
        return Page(items, if (o.has("TotalRecordCount")) o.optInt("TotalRecordCount") else items.size)
    }

    fun sortNames(json: String): List<String> {
        val arr = JSONObject(json).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.str("SortName") ?: o.str("Name") ?: ""
        }
    }

    fun libraries(json: String): List<Library> {
        val arr = JSONObject(json).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Library(o.optString("Id"), o.str("Name") ?: "Library", o.str("CollectionType"))
        }
    }

    fun playbackInfo(json: String): PlaybackInfo {
        val o = JSONObject(json)
        val error = o.str("ErrorCode")
        val arr = o.optJSONArray("MediaSources") ?: JSONArray()
        val sources = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::mediaSource) }
        if (sources.isEmpty()) {
            throw JellyfinException(
                when (error) {
                    "NotAllowed" -> "Your Jellyfin account isn't allowed to play this"
                    "NoCompatibleStream" -> "The server couldn't make a stream this phone can play"
                    "RateLimitExceeded" -> "The server is busy. Try again in a minute"
                    else -> "The server didn't send anything to play"
                },
            )
        }
        return PlaybackInfo(o.str("PlaySessionId"), sources)
    }

    private fun mediaSource(o: JSONObject): MediaSource {
        val arr = o.optJSONArray("MediaStreams") ?: JSONArray()
        val streams = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::stream) }
        return MediaSource(
            id = o.optString("Id"),
            container = o.str("Container"),
            supportsDirectPlay = o.optBoolean("SupportsDirectPlay"),
            supportsDirectStream = o.optBoolean("SupportsDirectStream"),
            transcodingUrl = o.str("TranscodingUrl"),
            streams = streams,
            defaultAudioIndex = o.int("DefaultAudioStreamIndex"),
            defaultSubtitleIndex = o.int("DefaultSubtitleStreamIndex"),
            runTimeTicks = o.long("RunTimeTicks"),
        )
    }

    private fun stream(o: JSONObject): MediaStream {
        val type = o.optString("Type")
        val language = o.str("Language")
        val title = o.str("DisplayTitle") ?: o.str("Title") ?: listOfNotNull(language, o.str("Codec")).joinToString(" ").ifEmpty { type }
        return MediaStream(
            index = o.optInt("Index"),
            type = type,
            title = title,
            language = language,
            codec = o.str("Codec"),
            isExternal = o.optBoolean("IsExternal"),
            deliveryMethod = o.str("DeliveryMethod"),
            deliveryUrl = o.str("DeliveryUrl"),
            isDefault = o.optBoolean("IsDefault"),
            channels = o.int("Channels"),
        )
    }

    fun report(r: PlaybackReport): String = JSONObject().apply {
        put("ItemId", r.itemId)
        put("MediaSourceId", r.mediaSourceId)
        r.playSessionId?.let { put("PlaySessionId", it) }
        put("PositionTicks", r.positionTicks)
        put("IsPaused", r.isPaused)
        put("CanSeek", true)
        put("PlayMethod", r.playMethod)
        r.audioIndex?.let { put("AudioStreamIndex", it) }
        r.subtitleIndex?.let { put("SubtitleStreamIndex", it) }
        r.event?.let { put("EventName", it) }
    }.toString()
}
