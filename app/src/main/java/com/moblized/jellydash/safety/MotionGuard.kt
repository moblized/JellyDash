package com.moblized.jellydash.safety

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides whether video may show. The picture goes away the moment the truck moves, and only comes
 * back once it has been stopped for a while and you tap "I'm parked".
 *
 * A phone can't see the gear selector, so a long red light looks like being parked. The wait and the
 * tap are there so the picture never comes back by itself.
 */
class MotionGuard(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    enum class Source { Car, Gps }

    enum class Reason {
        /** Parked and confirmed: video shows. */
        Parked,

        /** Moving right now. */
        Moving,

        /** Stopped after moving; the "I'm parked" button unlocks after [STOPPED_FOR_MS]. */
        Stopped,

        /** Stopped long enough. Waiting for the tap. */
        NeedsConfirm,
    }

    data class Status(
        val videoAllowed: Boolean,
        val reason: Reason,
        /** True when "I'm parked" can be tapped. */
        val canConfirm: Boolean,
        /** Seconds left before "I'm parked" unlocks. */
        val waitSeconds: Int,
        /** True when no speed reading has arrived recently, so movement can't be seen. */
        val blind: Boolean,
    )

    private var lastMotionAt: Long? = null
    private var confirmedAt: Long? = null
    private var lastSampleAt: Long? = null
    private var gpsFastCount = 0

    private val _status = MutableStateFlow(compute())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** A speed reading, in meters per second. GPS readings with poor accuracy are ignored. */
    @Synchronized
    fun onSpeed(metersPerSecond: Float, source: Source, accuracy: Float? = null) {
        if (metersPerSecond.isNaN() || metersPerSecond < 0) return
        if (source == Source.Gps && accuracy != null && accuracy > MAX_GPS_ACCURACY) return
        val now = clock()
        lastSampleAt = now
        val moving = when (source) {
            // The truck's own speed is exact.
            Source.Car -> metersPerSecond >= CAR_MOVING
            // GPS drifts a little when parked, so it takes a clear reading or two in a row.
            Source.Gps -> {
                if (metersPerSecond >= GPS_MOVING) gpsFastCount++ else gpsFastCount = 0
                metersPerSecond >= GPS_CLEARLY_MOVING || gpsFastCount >= 2
            }
        }
        if (moving) lastMotionAt = now
        publish()
    }

    /** The "I'm parked" tap. Returns false if it's too soon after moving. */
    @Synchronized
    fun confirmParked(): Boolean {
        val s = compute()
        if (!s.canConfirm && !s.videoAllowed) return false
        confirmedAt = clock()
        publish()
        return true
    }

    /** Re-checks timers; call about once a second while something is waiting on them. */
    @Synchronized
    fun tick() = publish()

    @Synchronized
    fun reset() {
        lastMotionAt = null
        confirmedAt = null
        lastSampleAt = null
        gpsFastCount = 0
        publish()
    }

    private fun publish() {
        _status.value = compute()
    }

    private fun compute(): Status {
        val now = clock()
        val motion = lastMotionAt
        val blind = lastSampleAt?.let { now - it > BLIND_AFTER_MS } ?: true
        val sinceMotion = if (motion == null) Long.MAX_VALUE else now - motion
        val confirmed = confirmedAt
        val confirmedSinceMotion = confirmed != null && (motion == null || confirmed > motion)
        return when {
            sinceMotion < MOVING_HOLD_MS ->
                Status(false, Reason.Moving, false, (STOPPED_FOR_MS / 1000).toInt(), blind)
            sinceMotion < STOPPED_FOR_MS && !confirmedSinceMotion ->
                Status(false, Reason.Stopped, false, ((STOPPED_FOR_MS - sinceMotion + 999) / 1000).toInt(), blind)
            confirmed != null && (motion == null || confirmed > motion) ->
                Status(true, Reason.Parked, false, 0, blind)
            else -> Status(false, Reason.NeedsConfirm, true, 0, blind)
        }
    }

    companion object {
        /** About 2 mph: the truck's own speed sensor reads 0 when stopped. */
        const val CAR_MOVING = 0.9f

        /** About 6 mph, two readings in a row. */
        const val GPS_MOVING = 2.7f

        /** About 11 mph in a single reading. */
        const val GPS_CLEARLY_MOVING = 5f

        /** GPS readings less accurate than this (meters per second) are skipped. */
        const val MAX_GPS_ACCURACY = 3f

        /** How long after the last movement it still counts as moving. */
        const val MOVING_HOLD_MS = 3_000L

        /** How long to be stopped before "I'm parked" can be tapped. */
        const val STOPPED_FOR_MS = 20_000L

        /** No speed reading for this long means movement can't be seen. */
        const val BLIND_AFTER_MS = 15_000L
    }
}
