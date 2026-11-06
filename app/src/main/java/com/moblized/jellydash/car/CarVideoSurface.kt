package com.moblized.jellydash.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.data.WhenMoving
import com.moblized.jellydash.jellyfin.Ticks
import com.moblized.jellydash.safety.MotionGuard

/**
 * Puts the video on the car display. Android Auto hands navigation apps a surface to draw their
 * map on; this turns that surface into a small private display and shows an ordinary video view on
 * it, so letterboxing and subtitles work the same as on a phone.
 */
class CarVideoSurface(private val carContext: CarContext) : SurfaceCallback {
    private var display: VirtualDisplay? = null
    private var screen: VideoPresentation? = null
    private var visible = Rect()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        val w = surfaceContainer.width
        val h = surfaceContainer.height
        if (w <= 0 || h <= 0) return
        val dm = carContext.getSystemService(DisplayManager::class.java) ?: return
        // No flags: a private display only this app can draw on.
        val vd = runCatching { dm.createVirtualDisplay("JellyDash", w, h, surfaceContainer.dpi, surface, 0) }.getOrNull() ?: return
        display = vd
        val p = VideoPresentation(carContext.applicationContext, vd.display)
        try {
            p.show()
            screen = p
            if (!visible.isEmpty) p.setVisibleArea(visible)
        } catch (_: Exception) {
            // The display went away before it could be used.
        }
        current = this
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        screen?.setVisibleArea(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = release()

    override fun onClick(x: Float, y: Float) {
        screen?.toggleControls()
    }

    fun showControls() {
        screen?.showControls()
    }

    fun release() {
        screen?.let { runCatching { it.dismiss() } }
        screen = null
        display?.release()
        display = null
    }

    companion object {
        /** The surface in use, so the player's buttons can bring the controls up. */
        @Volatile
        var current: CarVideoSurface? = null
    }
}

/** The video and what's drawn over it: title, progress, and the parked check's messages. */
@OptIn(UnstableApi::class)
class VideoPresentation(outer: Context, display: Display) : Presentation(outer, display, R.style.Theme_JellyDash_Car) {
    private val playback = Graph.playback(outer)
    private val guard = Graph.guard()
    private val prefs = Graph.prefs(outer)
    private val handler = Handler(Looper.getMainLooper())
    private var controlsUntil = 0L

    private lateinit var video: PlayerView
    private lateinit var top: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var center: LinearLayout
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var bottom: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var clock: TextView
    private lateinit var warning: TextView
    private lateinit var overlay: FrameLayout

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(size))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(3f).toFloat(), 0f, dp(1f).toFloat(), 0xCC000000.toInt())
        }

        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        video = PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(false)
        }
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = FrameLayout(ctx)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(28f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        title = text(22f, Color.WHITE, bold = true).apply { maxLines = 1 }
        subtitle = text(15f, 0xFFD6D0E6.toInt()).apply { maxLines = 1 }
        top.addView(title)
        top.addView(subtitle)
        overlay.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        center = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(16f), dp(24f), dp(16f))
        }
        spinner = ProgressBar(ctx).apply { isIndeterminate = true }
        headline = text(24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        detail = text(16f, 0xFFD6D0E6.toInt()).apply { gravity = Gravity.CENTER }
        center.addView(spinner, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        center.addView(headline)
        center.addView(detail)
        overlay.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(28f), dp(20f), dp(14f))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        warning = text(13f, 0xFFFFD27A.toInt())
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF00A4DC.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
        }
        clock = text(14f, Color.WHITE)
        bottom.addView(warning)
        bottom.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)))
        bottom.addView(clock)
        overlay.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        showControls()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        if (::video.isInitialized) video.player = null
        super.onStop()
    }

    /** Keeps the overlay clear of the buttons Android Auto draws on top. */
    fun setVisibleArea(area: Rect) {
        if (!::overlay.isInitialized) return
        val d = display
        val w = d.mode.physicalWidth
        val h = d.mode.physicalHeight
        overlay.setPadding(area.left.coerceAtLeast(0), area.top.coerceAtLeast(0), (w - area.right).coerceAtLeast(0), (h - area.bottom).coerceAtLeast(0))
    }

    fun showControls() {
        controlsUntil = SystemClock.uptimeMillis() + 5_000
        if (::overlay.isInitialized) update()
    }

    fun toggleControls() {
        controlsUntil = if (SystemClock.uptimeMillis() < controlsUntil) 0L else SystemClock.uptimeMillis() + 5_000
        update()
    }

    private fun update() {
        val state = playback.state.value
        val status = guard.status.value
        val player = playback.player
        if (video.player !== player) video.player = player

        // The picture only shows when the parked check allows it.
        video.visibility = if (status.videoAllowed && state?.error == null) View.VISIBLE else View.INVISIBLE

        title.text = state?.title ?: ""
        subtitle.text = state?.subtitle ?: ""
        subtitle.visibility = if (state?.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

        var big: String? = null
        var small: String? = null
        val keepAudio = prefs.currentSettings.whenMoving == WhenMoving.KeepAudio
        when {
            state == null -> big = "Loading…"
            state.error != null -> {
                big = "Can't play this"
                small = state.error
            }
            status.reason == MotionGuard.Reason.Moving -> {
                big = "Video is off while you drive"
                small = if (keepAudio) "The sound keeps playing." else "Paused until you park."
            }
            status.reason == MotionGuard.Reason.Stopped -> {
                big = "Stopped"
                small = "\"I'm parked\" unlocks in ${status.waitSeconds} s, once you've stayed put."
            }
            !status.videoAllowed -> {
                big = "Parked?"
                small = "Tap \"I'm parked\" to show the video."
            }
            state.ended -> big = "Finished"
            state.loading && !state.buffering -> small = "Starting…"
        }
        headline.text = big ?: ""
        headline.visibility = if (big == null) View.GONE else View.VISIBLE
        detail.text = small ?: ""
        detail.visibility = if (small == null) View.GONE else View.VISIBLE
        val busy = state != null && state.error == null && (state.loading || state.buffering)
        spinner.visibility = if (busy && status.videoAllowed) View.VISIBLE else View.GONE

        warning.text = if (status.blind) "Can't read your speed yet. Allow location for JellyDash on your phone so video turns off when you drive." else ""
        warning.visibility = if (status.blind) View.VISIBLE else View.GONE

        val position = player?.currentPosition ?: 0L
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: state?.item?.runTimeMs ?: 0L
        progress.progress = if (duration > 0) (position * 1000 / duration).toInt() else 0
        clock.text = if (duration > 0) "${Ticks.clock(position)} / ${Ticks.clock(duration)}" else Ticks.clock(position)

        val paused = state != null && !state.isPlaying
        val show = paused || big != null || SystemClock.uptimeMillis() < controlsUntil
        top.visibility = if (show) View.VISIBLE else View.GONE
        bottom.visibility = if (show || status.blind) View.VISIBLE else View.GONE
    }
}
