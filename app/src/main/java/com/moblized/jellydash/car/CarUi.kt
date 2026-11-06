package com.moblized.jellydash.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.moblized.jellydash.jellyfin.Item
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.Kind
import com.moblized.jellydash.jellyfin.Ticks
import com.moblized.jellydash.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun CarContext.icon(@DrawableRes res: Int, tinted: Boolean = true): CarIcon {
    val b = CarIcon.Builder(IconCompat.createWithResource(this, res))
    if (tinted) b.setTint(CarColor.DEFAULT)
    return b.build()
}

/** The Car App API level the head unit speaks, or 1 if it isn't known yet. */
fun CarContext.apiLevel(): Int = runCatching { carAppApiLevel }.getOrDefault(1)

private fun CarContext.limit(type: Int, fallback: Int): Int =
    if (apiLevel() < 2) fallback else runCatching { getCarService(ConstraintManager::class.java).getContentLimit(type) }.getOrDefault(fallback)

/** How many rows a list may have on this head unit. */
fun CarContext.listLimit(): Int = limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST, 6).coerceAtLeast(6)

/**
 * How many posters one grid shows. Capped so a screenful of posters stays a small message to the
 * head unit.
 */
fun CarContext.gridPage(): Int = limit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID, 12).coerceIn(6, 30)

fun placeholderFor(kind: Kind): Int = when (kind) {
    Kind.Series, Kind.Season, Kind.Episode -> R.drawable.ic_tv
    Kind.Folder -> R.drawable.ic_folder
    Kind.BoxSet -> R.drawable.ic_collection
    else -> R.drawable.ic_movie
}

/** One line under a title: what it is and how far along you are. */
fun describe(item: Item): String {
    val parts = mutableListOf<String>()
    when (item.kind) {
        Kind.Episode -> {
            val s = item.parentIndex
            val e = item.index
            if (s != null && e != null) parts += "S${s}E$e" else if (e != null) parts += "Episode $e"
        }
        Kind.Series -> item.year?.let { parts += it.toString() }
        Kind.Season -> item.childCount?.let { parts += if (it == 1) "1 episode" else "$it episodes" }
        Kind.Folder, Kind.BoxSet -> item.childCount?.let { parts += if (it == 1) "1 item" else "$it items" }
        else -> item.year?.let { parts += it.toString() }
    }
    val run = item.runTimeMs
    when {
        item.played -> parts += "Watched"
        item.inProgress && run != null -> parts += "${Ticks.duration(run - item.positionMs)} left"
        run != null && item.kind.playable -> parts += Ticks.duration(run)
        (item.kind == Kind.Series || item.kind == Kind.Season) && (item.unplayedCount ?: 0) > 0 -> parts += "${item.unplayedCount} unwatched"
    }
    return parts.joinToString(" · ")
}

/** The name to show for an item in a list: episodes get their show's name. */
fun headline(item: Item): String = if (item.kind == Kind.Episode && item.seriesName != null) item.seriesName else item.name

/**
 * Loads posters in the background and hands them out as car icons. When a batch arrives the screen
 * is told once, which the head unit counts as a refresh rather than a new step.
 */
class Posters(
    private val jellyfin: Jellyfin,
    private val scope: CoroutineScope,
    private val height: Int,
    private val onLoaded: () -> Unit,
) {
    private val pending = HashSet<String>()
    private var notifyJob: Job? = null

    fun icon(item: Item): CarIcon? {
        val url = jellyfin.imageUrl(item, height) ?: return null
        cache.get(url)?.let { return CarIcon.Builder(IconCompat.createWithBitmap(it)).build() }
        if (pending.add(url)) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { jellyfin.image(url)?.let { decode(it, height) } }
                if (bmp != null) {
                    cache.put(url, bmp)
                    scheduleNotify()
                }
            }
        }
        return null
    }

    private fun scheduleNotify() {
        if (notifyJob?.isActive == true) return
        notifyJob = scope.launch {
            delay(350)
            onLoaded()
        }
    }

    companion object {
        /** Keeps about 12 MB of posters, so going back to a screen doesn't load them again. */
        private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        fun decode(bytes: ByteArray, height: Int): Bitmap? {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (raw.height <= height) return raw
            val w = (raw.width * height.toFloat() / raw.height).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(raw, w, height, true).also { if (it != raw) raw.recycle() }
        }
    }
}
