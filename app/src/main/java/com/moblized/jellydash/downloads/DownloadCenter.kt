package com.moblized.jellydash.downloads

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.moblized.jellydash.BuildConfig
import com.moblized.jellydash.data.DownloadQuality
import com.moblized.jellydash.data.Prefs
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.JellyfinJson
import com.moblized.jellydash.jellyfin.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** One download, for the lists on the phone and in the car. */
data class DownloadEntry(
    val item: Item,
    val state: Int,
    val percent: Float,
    val bytes: Long,
    /** Expected size in bytes, when known. */
    val size: Long?,
    val quality: DownloadQuality,
    val converted: Boolean,
    val mediaSourceId: String,
    val waitingForWifi: Boolean = false,
) {
    val done: Boolean get() = state == Download.STATE_COMPLETED
    val failed: Boolean get() = state == Download.STATE_FAILED
    val active: Boolean get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
}

/**
 * Saves movies and episodes onto the phone so the truck plays them from storage: no buffering, no
 * cell data, and full quality. Built on Media3's download manager, which resumes after a lost
 * connection and waits for Wi-Fi when that's asked for.
 */
@OptIn(UnstableApi::class)
open class DownloadCenter(private val context: Context, private val prefs: Prefs, private val jellyfin: Jellyfin) {
    private val main = Handler(Looper.getMainLooper())
    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    private val dir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
    private val posterDir: File get() = File(context.filesDir, "posters").apply { mkdirs() }

    private val http = DefaultHttpDataSource.Factory()
        .setUserAgent("JellyDash/${BuildConfig.VERSION_NAME}")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20_000)
        .setReadTimeoutMs(60_000)

    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null

    /** Opens the download store. Called once when the app starts. */
    @Synchronized
    fun start(): DownloadManager {
        manager?.let { return it }
        val db = StandaloneDatabaseProvider(context)
        val c = SimpleCache(dir, NoOpCacheEvictor(), db)
        cache = c
        // One at a time, and each converted stream's pieces in order, so the server converts steadily.
        val m = DownloadManager(context, db, c, http, Runnable::run)
        m.maxParallelDownloads = 1
        m.requirements = requirements()
        m.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
            override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) = refresh()
        })
        manager = m
        return m
    }

    /** Downloads in progress refresh their percentage about once a second. */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
        }
    }

    private fun requirements() = Requirements(if (prefs.currentSettings.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun applySettings() {
        manager?.requirements = requirements()
    }

    private fun refresh() {
        val m = manager ?: return
        val all = LinkedHashMap<String, Download>()
        runCatching {
            m.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) all[c.download.request.id] = c.download }
        }
        for (d in m.currentDownloads) all[d.request.id] = d
        val waiting = m.isWaitingForRequirements
        _entries.value = all.values.sortedByDescending { it.startTimeMs }.mapNotNull { d -> entryOf(d, waiting) }
        main.removeCallbacks(ticker)
        if (_entries.value.any { it.state == Download.STATE_DOWNLOADING }) main.postDelayed(ticker, 1_000)
    }

    private fun entryOf(d: Download, waiting: Boolean): DownloadEntry? {
        val data = runCatching { JSONObject(String(d.request.data)) }.getOrNull() ?: return null
        val item = runCatching { JellyfinJson.item(data.getJSONObject("item")) }.getOrNull() ?: return null
        val quality = DownloadQuality.entries.firstOrNull { it.name == data.optString("quality") } ?: DownloadQuality.Truck
        val size = d.contentLength.takeIf { it > 0 } ?: data.optLong("size").takeIf { it > 0 }
        return DownloadEntry(
            item = item,
            state = d.state,
            percent = d.percentDownloaded.takeIf { it >= 0 } ?: 0f,
            bytes = d.bytesDownloaded,
            size = size,
            quality = quality,
            converted = data.optBoolean("converted"),
            mediaSourceId = data.optString("mediaSourceId", item.id),
            waitingForWifi = waiting && d.state == Download.STATE_QUEUED,
        )
    }

    /** For tests: pretend these are on the phone. */
    @androidx.annotation.VisibleForTesting
    fun setEntriesForTest(list: List<DownloadEntry>) {
        _entries.value = list
    }

    fun entry(itemId: String): DownloadEntry? = _entries.value.firstOrNull { it.item.id == itemId }

    /** A finished download of this item, if there is one. */
    fun completed(itemId: String): DownloadEntry? = entry(itemId)?.takeIf { it.done }

    /** Asks the server how to save [item], then queues it. */
    suspend fun download(item: Item, quality: DownloadQuality = prefs.currentSettings.downloadQuality) {
        require(item.kind.playable) { "Only movies, episodes and videos can be downloaded" }
        start()
        val plan = DownloadPlanner.plan(jellyfin, item, quality)
        savePoster(item)
        http.setDefaultRequestProperties(jellyfin.streamHeaders())
        val data = JSONObject()
            .put("item", JellyfinJson.itemToJson(item))
            .put("quality", quality.name)
            .put("converted", plan.converted)
            .put("mediaSourceId", plan.mediaSourceId)
            .put("size", plan.estimatedBytes ?: 0)
        val request = DownloadRequest.Builder(item.id, Uri.parse(plan.url))
            .setMimeType(if (plan.isHls) MimeTypes.APPLICATION_M3U8 else null)
            .setCustomCacheKey(if (plan.isHls) null else "jellydash-${item.id}")
            .setData(data.toString().toByteArray())
            .build()
        withContext(Dispatchers.Main) {
            DownloadService.sendAddDownload(context, JellyDashDownloadService::class.java, request, false)
        }
    }

    fun remove(itemId: String) {
        DownloadService.sendRemoveDownload(context, JellyDashDownloadService::class.java, itemId, false)
        File(posterDir, "$itemId.jpg").delete()
    }

    /** What to give the player for a finished download. */
    fun mediaItem(itemId: String): MediaItem? {
        start()
        val d = manager?.currentDownloads?.firstOrNull { it.request.id == itemId }
            ?: runCatching { manager?.downloadIndex?.getDownload(itemId) }.getOrNull()
            ?: return null
        if (d.state != Download.STATE_COMPLETED) return null
        return d.request.toMediaItem()
    }

    /**
     * The player's data source: reads finished downloads from storage, and streams everything else
     * straight from the server without filling the phone up.
     */
    fun playbackSource(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val c = cache
        if (c == null) {
            upstream.createDataSource()
        } else {
            CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)
                .createDataSource()
        }
    }

    /** The poster saved with a download, for showing it with no connection. */
    fun posterFile(itemId: String): File? = File(posterDir, "$itemId.jpg").takeIf { it.exists() }

    private suspend fun savePoster(item: Item) {
        val url = jellyfin.imageUrl(item, 360) ?: return
        val bytes = runCatching { jellyfin.image(url) }.getOrNull() ?: return
        withContext(Dispatchers.IO) { runCatching { File(posterDir, "${item.id}.jpg").writeBytes(bytes) } }
    }

    /** Space used by downloads, in bytes. */
    fun usedBytes(): Long = cache?.cacheSpace ?: 0

    companion object {
        fun canDownload(item: Item) = item.kind.playable || item.kind == Kind.Season || item.kind == Kind.Series
    }
}
