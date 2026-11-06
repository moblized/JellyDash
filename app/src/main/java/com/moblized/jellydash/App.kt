package com.moblized.jellydash

import android.app.Application
import android.content.Context
import com.moblized.jellydash.data.Prefs
import com.moblized.jellydash.jellyfin.Jellyfin
import com.moblized.jellydash.jellyfin.JellyfinClient
import com.moblized.jellydash.playback.PlaybackController
import com.moblized.jellydash.safety.MotionGuard

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Graph.prefs(this)
        IconSwitcher.apply(this, prefs.isDark(this))
    }
}

/** The app's shared pieces. The phone screens and the Android Auto screens use the same ones. */
object Graph {
    private var prefs: Prefs? = null
    private var jellyfin: Jellyfin? = null
    private var playback: PlaybackController? = null
    private var guard: MotionGuard? = null

    @Synchronized
    fun prefs(context: Context): Prefs = prefs ?: Prefs(context.applicationContext).also { prefs = it }

    @Synchronized
    fun jellyfin(context: Context): Jellyfin = jellyfin ?: JellyfinClient(prefs(context)).also { jellyfin = it }

    @Synchronized
    fun guard(): MotionGuard = guard ?: MotionGuard().also { guard = it }

    @Synchronized
    fun playback(context: Context): PlaybackController =
        playback ?: PlaybackController(context.applicationContext, prefs(context), jellyfin(context), guard()).also { playback = it }

    /** Tests swap in a fake server. */
    @Synchronized
    fun override(prefs: Prefs? = null, jellyfin: Jellyfin? = null, guard: MotionGuard? = null) {
        if (prefs != null) this.prefs = prefs
        if (jellyfin != null) this.jellyfin = jellyfin
        if (guard != null) this.guard = guard
        playback = null
    }

    @Synchronized
    fun reset() {
        prefs = null
        jellyfin = null
        playback = null
        guard = null
    }
}
