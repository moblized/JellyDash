package com.moblized.jellydash.jellyfin

import java.io.IOException

enum class Sort { Name, Newest }

/** Where to browse: a library or folder, and whether to list every video under it instead of its folders. */
data class Browse(val parentId: String, val collectionType: String?, val flatten: Boolean = false)

enum class ReportKind { Start, Progress, Stopped }

/** A failure worth showing as is. */
class JellyfinException(message: String, val code: Int? = null, cause: Throwable? = null) : IOException(message, cause) {
    val signedOut: Boolean get() = code == 401
}

/** Everything the app asks of a Jellyfin server. */
interface Jellyfin {
    suspend fun serverInfo(server: String): ServerInfo
    suspend fun signIn(server: String, username: String, password: String): SignIn

    suspend fun libraries(): List<Library>
    suspend fun resume(limit: Int): List<Item>
    suspend fun nextUp(limit: Int): List<Item>

    /** The next episode of one show to watch, or null when you're caught up. */
    suspend fun nextUpFor(seriesId: String): Item?
    suspend fun browse(where: Browse, sort: Sort, start: Int, limit: Int): Page

    /** Sort names of everything [browse] would list, in order, for splitting a big library into A–Z groups. */
    suspend fun sortNames(where: Browse): List<String>
    suspend fun seasons(seriesId: String): List<Item>
    suspend fun episodes(seriesId: String, seasonId: String?): List<Item>
    suspend fun item(id: String): Item
    suspend fun search(term: String, limit: Int): List<Item>

    suspend fun playbackInfo(itemId: String, request: PlaybackRequest): PlaybackInfo
    suspend fun report(kind: ReportKind, report: PlaybackReport)
    suspend fun stopEncoding(playSessionId: String)

    /** Marks something watched. */
    suspend fun markPlayed(itemId: String)

    /** Raw image bytes, or null if the server has none. */
    suspend fun image(url: String): ByteArray?
    fun imageUrl(item: Item, height: Int): String?

    /** The address to stream a file from as is. */
    fun directUrl(itemId: String, source: MediaSource, playSessionId: String?): String

    /** A server path made into a full address that carries the sign-in. */
    fun signedUrl(path: String): String

    /** Headers every stream request carries. */
    fun streamHeaders(): Map<String, String>
}
