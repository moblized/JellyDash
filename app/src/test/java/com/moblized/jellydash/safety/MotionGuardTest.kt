package com.moblized.jellydash.safety

import com.moblized.jellydash.safety.MotionGuard.Reason
import com.moblized.jellydash.safety.MotionGuard.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGuardTest {
    private var now = 1_000_000L
    private val guard = MotionGuard { now }

    private fun advance(ms: Long) {
        now += ms
        guard.tick()
    }

    private val status get() = guard.status.value

    @Test
    fun startsWaitingForATapAndShowsVideoAfterIt() {
        assertFalse(status.videoAllowed)
        assertEquals(Reason.NeedsConfirm, status.reason)
        assertTrue(guard.confirmParked())
        assertTrue(status.videoAllowed)
        assertEquals(Reason.Parked, status.reason)
    }

    @Test
    fun movingHidesTheVideoAtOnce() {
        guard.confirmParked()
        guard.onSpeed(0.0f, Source.Car)
        assertTrue(status.videoAllowed)
        guard.onSpeed(1.5f, Source.Car)
        assertFalse(status.videoAllowed)
        assertEquals(Reason.Moving, status.reason)
    }

    @Test
    fun aRedLightDoesNotBringItBack() {
        guard.confirmParked()
        guard.onSpeed(12f, Source.Gps, 0.5f)
        advance(4_000)
        guard.onSpeed(0f, Source.Gps, 0.5f)
        assertEquals(Reason.Stopped, status.reason)
        assertFalse(status.canConfirm)
        assertFalse("too soon", guard.confirmParked())
        assertFalse(status.videoAllowed)

        advance(10_000)
        assertEquals(Reason.Stopped, status.reason)
        assertEquals(6, status.waitSeconds)

        advance(7_000)
        // Stopped long enough, but it still takes the tap.
        assertEquals(Reason.NeedsConfirm, status.reason)
        assertFalse(status.videoAllowed)
        assertTrue(guard.confirmParked())
        assertTrue(status.videoAllowed)
    }

    @Test
    fun gpsDriftWhileParkedIsIgnored() {
        guard.confirmParked()
        guard.onSpeed(3.0f, Source.Gps, 1f)
        assertTrue("one middling reading isn't driving", status.videoAllowed)
        guard.onSpeed(0.2f, Source.Gps, 1f)
        guard.onSpeed(3.0f, Source.Gps, 1f)
        assertTrue(status.videoAllowed)
        guard.onSpeed(8f, Source.Gps, 9f)
        assertTrue("inaccurate readings are skipped", status.videoAllowed)
    }

    @Test
    fun twoMiddlingReadingsInARowAreDriving() {
        guard.confirmParked()
        guard.onSpeed(3.0f, Source.Gps, 1f)
        guard.onSpeed(3.2f, Source.Gps, 1f)
        assertFalse(status.videoAllowed)
    }

    @Test
    fun noReadingsMeansBlind() {
        assertTrue(status.blind)
        guard.onSpeed(0f, Source.Gps, 0.4f)
        assertFalse(status.blind)
        advance(MotionGuard.BLIND_AFTER_MS + 1)
        assertTrue(status.blind)
    }

    @Test
    fun tappingPlayRightAfterDrivingDoesNotShowVideo() {
        guard.onSpeed(15f, Source.Car)
        advance(5_000)
        guard.onSpeed(0f, Source.Car)
        assertFalse(guard.confirmParked())
        assertFalse(status.videoAllowed)
    }
}
