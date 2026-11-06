package com.moblized.jellydash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.moblized.jellydash.Graph
import com.moblized.jellydash.R
import com.moblized.jellydash.playback.NowPlaying
import com.moblized.jellydash.safety.MotionGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The video screen. The picture is drawn on the map surface Android Auto gives navigation apps,
 * with the buttons on top: Close, Play/Pause (or "I'm parked"), and Audio & subtitles, plus
 * back 10 s and ahead 30 s by the map controls.
 */
class PlayerScreen(carContext: CarContext) : Screen(carContext) {
    private val playback = Graph.playback(carContext)
    private val guard = Graph.guard()

    /** What the buttons depend on. Anything else (the clock, the progress bar) is drawn on the surface. */
    private data class Buttons(
        val playing: Boolean,
        val ended: Boolean,
        val error: Boolean,
        val canConfirm: Boolean,
        val videoAllowed: Boolean,
        val waiting: Boolean,
        val hasTracks: Boolean,
        val canNext: Boolean,
    )

    private var buttons = Buttons(false, false, false, false, false, false, false, false)

    init {
        lifecycleScope.launch {
            playback.state.combine(guard.status) { s, g -> buttonsFor(s, g) }
                .distinctUntilChanged()
                .collect {
                    buttons = it
                    invalidate()
                }
        }
        // The "I'm parked" countdown, and the moment it unlocks.
        lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                guard.tick()
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Leaving the player stops the video and saves your place on the server.
                playback.stop()
            }
        })
    }

    private fun buttonsFor(s: NowPlaying?, g: MotionGuard.Status) = Buttons(
        playing = s?.isPlaying == true || (s?.loading == true && s.error == null),
        ended = s?.ended == true,
        error = s?.error != null,
        canConfirm = g.canConfirm,
        videoAllowed = g.videoAllowed,
        waiting = g.reason == MotionGuard.Reason.Stopped,
        hasTracks = s?.plan?.let { it.source.audio.size > 1 || it.source.subtitles.isNotEmpty() } == true,
        canNext = playback.canPlayNext,
    )

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
        strip.addAction(
            Action.Builder()
                .setIcon(carContext.icon(R.drawable.ic_close))
                .setTitle("Close")
                .setOnClickListener { screenManager.pop() }
                .build(),
        )
        val b = buttons
        when {
            !b.videoAllowed && b.canConfirm -> strip.addAction(
                Action.Builder()
                    .setIcon(carContext.icon(R.drawable.ic_parked))
                    .setTitle("I'm parked")
                    .apply { if (carContext.apiLevel() >= 4) setFlags(Action.FLAG_PRIMARY) }
                    .setOnClickListener {
                        guard.confirmParked()
                        surface()?.showControls()
                    }
                    .build(),
            )
            b.ended && b.canNext -> strip.addAction(
                Action.Builder()
                    .setIcon(carContext.icon(R.drawable.ic_next))
                    .setTitle("Next episode")
                    .setOnClickListener { playback.playNextEpisode() }
                    .build(),
            )
            else -> strip.addAction(
                Action.Builder()
                    .setIcon(carContext.icon(if (b.playing && !b.ended) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener {
                        playback.togglePlay()
                        surface()?.showControls()
                    }
                    .build(),
            )
        }
        if (b.hasTracks) {
            strip.addAction(
                Action.Builder()
                    .setIcon(carContext.icon(R.drawable.ic_subtitles))
                    .setOnClickListener { screenManager.push(TracksScreen(carContext)) }
                    .build(),
            )
        }
        val t = NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setBackgroundColor(CarColor.createCustom(0xFF000000.toInt(), 0xFF000000.toInt()))
        if (carContext.apiLevel() >= 2) {
            t.setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(carContext.icon(R.drawable.ic_back10))
                            .setOnClickListener {
                                playback.seekBy(-10_000)
                                surface()?.showControls()
                            }
                            .build(),
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(carContext.icon(R.drawable.ic_fwd30))
                            .setOnClickListener {
                                playback.seekBy(30_000)
                                surface()?.showControls()
                            }
                            .build(),
                    )
                    .build(),
            )
        }
        return t.build()
    }

    private fun surface(): CarVideoSurface? = CarVideoSurface.current
}
