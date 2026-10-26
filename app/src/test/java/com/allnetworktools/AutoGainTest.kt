package com.allnetworktools

import com.allnetworktools.data.sdr.AutoGain
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGainTest {
    private fun noise(amplitude: Int, seed: Int = 1): ByteArray {
        val r = Random(seed)
        return ByteArray(262_144) { (r.nextInt(-amplitude, amplitude + 1)).coerceIn(-128, 127).toByte() }
    }

    @Test fun ladderUsesOfferedValuesOnly() {
        val g = AutoGain(listOf(16, 24, 32, 40), listOf(20, 30, 40), 32, 30)
        assertEquals(32 to 30, g.current)
        assertTrue(g.ladder.all { it.first in listOf(16, 24, 32, 40) && it.second in listOf(20, 30, 40) })
        assertEquals(g.ladder.map { it.first + it.second }.sorted(), g.ladder.map { it.first + it.second })
    }

    @Test fun stepsDownOnClippingAndUpWhenQuiet() {
        val g = AutoGain(listOf(16, 24, 32, 40), listOf(20, 30, 40), 32, 30)
        var t = 0L
        val loud = noise(300)
        var step: Pair<Int, Int>? = null
        while (step == null && t < 2_000_000_000L) { step = g.feed(loud, loud.size, t); t += 50_000_000L }
        assertTrue(step!!.first + step.second < 62)
        // Right after a step it waits for the radio to settle.
        assertNull(g.feed(loud, loud.size, t))

        val quiet = noise(2)
        val before = g.current.first + g.current.second
        var up: Pair<Int, Int>? = null
        while (up == null && t < 6_000_000_000L) { up = g.feed(quiet, quiet.size, t); t += 50_000_000L }
        assertTrue(up!!.first + up.second > before)
    }

    @Test fun holdsAGoodLevel() {
        val g = AutoGain(listOf(16, 24, 32, 40), listOf(20, 30, 40), 32, 30)
        val ok = noise(12)
        var t = 0L
        repeat(60) { assertNull(g.feed(ok, ok.size, t)); t += 50_000_000L }
        assertEquals(32 to 30, g.current)
    }
}
