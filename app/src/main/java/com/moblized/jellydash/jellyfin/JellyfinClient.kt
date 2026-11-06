package com.moblized.jellydash.jellyfin

import android.os.Build
import com.moblized.jellydash.BuildConfig
import com.moblized.jellydash.data.Account
import com.moblized.jellydash.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Talks to your Jellyfin server over its REST API. */
class JellyfinClient(
    private val prefs: Prefs,
    private val clientName: String = "JellyDash",
    private val version: String = BuildConfig.VERSION_NAME,
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
) : Jellyfin {

    private val fields = "Overview,ChildCount,PrimaryImageAspectRatio"

    private fun account(): Account = prefs.currentAccount ?: throw JellyfinException("Sign in on your phone first", 401)

    private fun authHeader(token: String?): String {
        fun q(s: String) = s.replace("\"", "'")
        val parts = mutableListOf(
            "Client=\"${q(clientName)}\"",
            "Device=\"${q(deviceName)}\"",
            "DeviceId=\"${q(prefs.deviceId)}\"",
            "Version=\"${q(version)}\"",
        )
        if (token != null) parts += "Token=\"${q(token)}\""
        return "MediaBrowser " + parts.joinToString(", ")
    }

    override fun streamHeaders(): Map<String, String> = mapOf("Authorization" to authHeader(prefs.currentAccount?.token))

    // ---- HTTP ----

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun query(params: Map<String, Any?>): String = params.entries
        .filter { it.value != null }
        .joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }

    private suspend fun call(
        server: String,
        method: String,
        path: String,
        token: String?,
        body: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val url = server.trimEnd('/') + path
        val c = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: IOException) {
            throw JellyfinException("That server address doesn't look right", cause = e)
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", authHeader(token))
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = when (code) {
                    401 -> if (token == null) "Wrong username or password" else "Your sign-in has expired. Sign in again on your phone"
                    403 -> "Your Jellyfin account isn't allowed to do that"
                    404 -> "The server doesn't have that"
                    in 500..599 -> "The server had a problem (error $code)"
                    else -> "The server answered with error $code"
                }
                throw JellyfinException(msg, code)
            }
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: JellyfinException) {
            throw e
        } catch (e: UnknownHostException) {
            throw JellyfinException("Can't find that server. Check the address and your signal", cause = e)
        } catch (e: ConnectException) {
            throw JellyfinException("Can't reach the server. Is it on, and reachable from outside your home?", cause = e)
        } catch (e: SocketTimeoutException) {
            throw JellyfinException("The server took too long to answer", cause = e)
        } catch (e: SSLException) {
            throw JellyfinException("The server's HTTPS certificate wasn't accepted", cause = e)
        } catch (e: IOException) {
            throw JellyfinException("Couldn't talk to the server (${e.message ?: e.javaClass.simpleName})", cause = e)
        } finally {
            c.disconnect()
        }
    }

    private suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): String {
        val a = account()
        val q = query(params)
        return call(a.server, "GET", if (q.isEmpty()) path else "$path?$q", a.token)
    }

    /** Newer servers moved some endpoints; older ones still answer the old paths. */
    private suspend fun getEither(path: String, oldPath: String, params: Map<String, Any?>): String = try {
        get(path, params)
    } catch (e: JellyfinException) {
        if (e.code == 404) get(oldPath, params - "userId") else throw e
    }

    private suspend fun post(path: String, body: String, params: Map<String, Any?> = emptyMap()): String {
        val a = account()
        val q = query(params)
        return call(a.server, "POST", if (q.isEmpty()) path else "$path?$q", a.token, body)
    }

    // ---- Sign-in ----

    override suspend fun serverInfo(server: String): ServerInfo =
        JellyfinJson.serverInfo(call(server, "GET", "/System/Info/Public", null))

    override suspend fun signIn(server: String, username: String, password: String): SignIn {
        val body = JSONObject().put("Username", username).put("Pw", password).toString()
        return JellyfinJson.signIn(call(server, "POST", "/Users/AuthenticateByName", null, body))
    }

    // ---- Browsing ----

    private fun userId() = account().userId

    override suspend fun libraries(): List<Library> {
        val uid = userId()
        return JellyfinJson.libraries(getEither("/UserViews", "/Users/$uid/Views", mapOf("userId" to uid)))
            .filter { it.isVideo }
    }

    override suspend fun resume(limit: Int): List<Item> {
        val uid = userId()
        val p = mapOf(
            "userId" to uid,
            "limit" to limit,
            "mediaTypes" to "Video",
            "fields" to fields,
            "enableImageTypes" to "Primary",
            "imageTypeLimit" to 1,
        )
        return JellyfinJson.itemList(getEither("/UserItems/Resume", "/Users/$uid/Items/Resume", p))
    }

    override suspend fun nextUp(limit: Int): List<Item> = JellyfinJson.itemList(
        get(
            "/Shows/NextUp",
            mapOf(
                "userId" to userId(),
                "limit" to limit,
                "fields" to fields,
                "enableResumable" to false,
                "enableImageTypes" to "Primary",
                "imageTypeLimit" to 1,
            ),
        ),
    )

    override suspend fun nextUpFor(seriesId: String): Item? = JellyfinJson.itemList(
        get(
            "/Shows/NextUp",
            mapOf("userId" to userId(), "seriesId" to seriesId, "limit" to 1, "fields" to fields, "enableResumable" to true),
        ),
    ).firstOrNull()

    private fun browseParams(where: Browse, sort: Sort): Map<String, Any?> {
        val p = linkedMapOf<String, Any?>("userId" to userId(), "parentId" to where.parentId)
        when {
            where.flatten -> {
                p["recursive"] = true
                p["includeItemTypes"] = "Movie,Episode,Video,MusicVideo"
            }
            where.collectionType == "movies" -> {
                p["recursive"] = true
                p["includeItemTypes"] = "Movie"
            }
            where.collectionType == "tvshows" -> {
                p["recursive"] = true
                p["includeItemTypes"] = "Series"
            }
            where.collectionType == "boxsets" -> {
                p["recursive"] = true
                p["includeItemTypes"] = "BoxSet"
            }
            where.collectionType == "musicvideos" -> {
                p["recursive"] = true
                p["includeItemTypes"] = "MusicVideo"
            }
            else -> {
                // Folder by folder, without the music and photos that can share a mixed library.
                p["recursive"] = false
                p["excludeItemTypes"] = "Audio,MusicAlbum,MusicArtist,Photo,PhotoAlbum,Book,AudioBook,Playlist"
            }
        }
        when (sort) {
            Sort.Name -> {
                p["sortBy"] = "SortName"
                p["sortOrder"] = "Ascending"
            }
            Sort.Newest -> {
                p["sortBy"] = "DateCreated,SortName"
                p["sortOrder"] = "Descending"
            }
        }
        return p
    }

    override suspend fun browse(where: Browse, sort: Sort, start: Int, limit: Int): Page {
        val uid = userId()
        val p = browseParams(where, sort) + mapOf(
            "startIndex" to start,
            "limit" to limit,
            "fields" to fields,
            "enableImageTypes" to "Primary",
            "imageTypeLimit" to 1,
            "enableTotalRecordCount" to true,
        )
        return JellyfinJson.page(getEither("/Items", "/Users/$uid/Items", p))
    }

    override suspend fun sortNames(where: Browse): List<String> {
        val uid = userId()
        val p = browseParams(where, Sort.Name) + mapOf(
            "fields" to "SortName",
            "enableImages" to false,
            "enableUserData" to false,
            "limit" to 20_000,
        )
        return JellyfinJson.sortNames(getEither("/Items", "/Users/$uid/Items", p))
    }

    override suspend fun seasons(seriesId: String): List<Item> = JellyfinJson.itemList(
        get("/Shows/$seriesId/Seasons", mapOf("userId" to userId(), "fields" to fields)),
    )

    override suspend fun episodes(seriesId: String, seasonId: String?): List<Item> = JellyfinJson.itemList(
        get(
            "/Shows/$seriesId/Episodes",
            mapOf("userId" to userId(), "seasonId" to seasonId, "fields" to fields, "enableImageTypes" to "Primary", "imageTypeLimit" to 1),
        ),
    )

    override suspend fun item(id: String): Item {
        val uid = userId()
        val json = getEither("/Items/$id", "/Users/$uid/Items/$id", mapOf("userId" to uid, "fields" to fields))
        return JellyfinJson.item(JSONObject(json))
    }

    override suspend fun search(term: String, limit: Int): List<Item> {
        val uid = userId()
        val p = mapOf(
            "userId" to uid,
            "searchTerm" to term,
            "recursive" to true,
            "includeItemTypes" to "Movie,Series,Episode,Video,MusicVideo",
            "limit" to limit,
            "fields" to fields,
            "enableImageTypes" to "Primary",
            "imageTypeLimit" to 1,
        )
        return JellyfinJson.itemList(getEither("/Items", "/Users/$uid/Items", p))
    }

    // ---- Playback ----

    override suspend fun playbackInfo(itemId: String, request: PlaybackRequest): PlaybackInfo {
        val body = JSONObject().apply {
            put("UserId", userId())
            put("DeviceProfile", DeviceProfile.build(request.maxBitrate))
            put("MaxStreamingBitrate", request.maxBitrate)
            request.audioIndex?.let { put("AudioStreamIndex", it) }
            request.subtitleIndex?.let { put("SubtitleStreamIndex", it) }
            request.mediaSourceId?.let { put("MediaSourceId", it) }
            put("EnableDirectPlay", !request.forceServerStream)
            put("EnableDirectStream", !request.forceServerStream)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", true)
        }.toString()
        return JellyfinJson.playbackInfo(post("/Items/$itemId/PlaybackInfo", body, mapOf("userId" to userId())))
    }

    override suspend fun report(kind: ReportKind, report: PlaybackReport) {
        val path = when (kind) {
            ReportKind.Start -> "/Sessions/Playing"
            ReportKind.Progress -> "/Sessions/Playing/Progress"
            ReportKind.Stopped -> "/Sessions/Playing/Stopped"
        }
        post(path, JellyfinJson.report(report))
    }

    override suspend fun stopEncoding(playSessionId: String) {
        val a = account()
        call(a.server, "DELETE", "/Videos/ActiveEncodings?" + query(mapOf("deviceId" to prefs.deviceId, "playSessionId" to playSessionId)), a.token)
    }

    // ---- Addresses ----

    override fun imageUrl(item: Item, height: Int): String? {
        val a = prefs.currentAccount ?: return null
        val id = item.imageItemId ?: return null
        val tag = item.imageTag?.let { "&tag=${enc(it)}" } ?: ""
        return "${a.server.trimEnd('/')}/Items/$id/Images/Primary?fillHeight=$height&quality=85$tag"
    }

    override suspend fun image(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", authHeader(prefs.currentAccount?.token))
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes() }
        } catch (_: IOException) {
            null
        } finally {
            c.disconnect()
        }
    }

    override fun directUrl(itemId: String, source: MediaSource, playSessionId: String?): String {
        val a = account()
        return a.server.trimEnd('/') + "/Videos/$itemId/stream?" + query(
            mapOf(
                "static" to true,
                "mediaSourceId" to source.id,
                "deviceId" to prefs.deviceId,
                "playSessionId" to playSessionId,
                "api_key" to a.token,
            ),
        )
    }

    override fun signedUrl(path: String): String {
        val a = account()
        val full = if (path.startsWith("http://") || path.startsWith("https://")) path else a.server.trimEnd('/') + "/" + path.trimStart('/')
        val lower = full.lowercase()
        if ("api_key=" in lower || "apikey=" in lower) return full
        return full + (if ('?' in full) "&" else "?") + "api_key=" + enc(a.token)
    }
}
