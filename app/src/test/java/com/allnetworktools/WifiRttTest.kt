package com.allnetworktools

import com.allnetworktools.data.RttTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRttTest {
    @Test fun theMedianIgnoresAStrayReading() {
        val t = RttTrack("aa", "Box", 5180)
        assertNull(t.distanceM)
        listOf(4000, 4200, 3900, 15000, 4100).forEachIndexed { i, mm -> t.add(mm, 300, -50, i * 1000L, true) }
        assertEquals(4.1, t.distanceM!!, 0.05)
        assertTrue(t.spreadM!! < 0.5)
        assertEquals(5, t.successes)
    }

    @Test fun calibrationTakesTheOffsetAway() {
        val t = RttTrack("aa", "Box", 5180)
        repeat(5) { t.add(2500, 200, -40, it * 1000L, true) }
        t.calibrateHere()
        assertEquals(0.0, t.distanceM!!, 1e-9)
        repeat(7) { t.add(6500, 200, -55, 10_000L + it * 1000L, true) }
        assertEquals(4.0, t.distanceM!!, 1e-9)
    }

    @Test fun walkingAwayGivesAPositiveSpeed() {
        val t = RttTrack("aa", "Box", 5180)
        for (s in 0..12) t.add(2000 + s * 1000, 200, -50, s * 1000L, true)
        assertTrue(t.speedMs!! > 0.5)
    }

    @Test fun failuresCountAsAttemptsOnly() {
        val t = RttTrack("aa", "Box", 5180)
        t.add(0, 0, 0, 0, false)
        t.add(3000, 100, -50, 1000, true)
        assertEquals(2, t.attempts)
        assertEquals(1, t.successes)
    }
}
