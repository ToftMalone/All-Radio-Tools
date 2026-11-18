package com.allnetworktools

import com.allnetworktools.data.sdr.SoftMend
import org.junit.Assert.assertTrue

/** What soft mending wins on one decoder: [decode] counts the frames it got right (and fails on any wrong one). */
object SoftGain {
    fun measure(name: String, minGain: Int, decode: () -> Int): Pair<Int, Int> {
        SoftMend.enabled = false
        val without = try { decode() } finally { SoftMend.enabled = true }
        for (i in 0 until 3) SoftMend.stats.set(i, 0)
        val with = decode()
        println("GAIN $name: $without without mending, $with with (mend calls ${SoftMend.stats[0]}, refused as noise ${SoftMend.stats[1]}, mended ${SoftMend.stats[2]})")
        assertTrue("$name: $without → $with", with >= without + minGain)
        return without to with
    }
}
