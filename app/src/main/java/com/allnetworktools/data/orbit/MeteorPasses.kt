package com.allnetworktools.data.orbit

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A Russian weather satellite that broadcasts LRPT images in the 137 MHz band. */
data class MeteorSat(val name: String, val catalog: Int, val freqMhz: Double)

object MeteorSats {
    val all = listOf(
        MeteorSat("Meteor-M2 3", 57166, 137.900),
        MeteorSat("Meteor-M2 4", 59051, 137.100),
    )
}

data class MeteorPass(
    val sat: MeteorSat,
    val riseMs: Long,
    val setMs: Long,
    val maxAtMs: Long,
    val maxElevation: Double,
    val riseAzimuth: Double,
    val setAzimuth: Double,
)

/** Element sets of the weather satellites from CelesTrak, cached so predictions work offline. */
open class WeatherTleRepository(context: Context?) {
    private val file = context?.let { File(it.cacheDir, "weather.tle") }

    open suspend fun load(forceRefresh: Boolean = false): List<Tle> = withContext(Dispatchers.IO) {
        val cached = file?.takeIf { it.exists() && it.length() > 0 }
        val fresh = cached != null && System.currentTimeMillis() - cached.lastModified() < MaxAgeMs
        if (cached != null && fresh && !forceRefresh) return@withContext Tle.parseAll(cached.readText())
        try {
            val text = download()
            file?.writeText(text)
            Tle.parseAll(text)
        } catch (e: Exception) {
            if (cached != null) Tle.parseAll(cached.readText()) else throw e
        }
    }

    private fun download(): String {
        val c = URL(Url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "AllRadioTools")
        try {
            if (c.responseCode != 200) throw java.io.IOException(S(R.string.celestrak_answered, c.responseCode))
            val text = c.inputStream.bufferedReader().readText()
            if (Tle.parseAll(text).isEmpty()) throw java.io.IOException(S(R.string.unreadable_answer_from_celestrak))
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val Url = "https://celestrak.org/NORAD/elements/gp.php?GROUP=weather&FORMAT=tle"
        const val MaxAgeMs = 12 * 3_600_000L
    }
}

object MeteorPredictor {
    /**
     * Passes of [sat] (from its element set [tle]) above [maskDeg] between [startMs] and [endMs], sampled every
     * [stepMs] and refined to the second. Passes that never reach [minPeakDeg] are dropped: low passes are too weak
     * for a clean image.
     */
    fun passes(tle: Tle, sat: MeteorSat, obs: Observer, startMs: Long, endMs: Long, maskDeg: Double = 5.0, minPeakDeg: Double = 15.0, stepMs: Long = 30_000L): List<MeteorPass> {
        val sgp4 = Sgp4(tle)
        fun look(t: Long): Look? {
            val jd = Sgp4.jdFromUnixMs(t)
            val r = sgp4.position((jd - sgp4.jdEpoch) * 1440.0) ?: return null
            return obs.look(r, jd)
        }
        fun crossing(a0: Long, b0: Long): Long {
            var a = a0
            var b = b0
            val rising = (look(a)?.elevation ?: -90.0) < maskDeg
            while (b - a > 1000) {
                val m = (a + b) / 2
                val above = (look(m)?.elevation ?: -90.0) >= maskDeg
                if (above == rising) b = m else a = m
            }
            return (a + b) / 2
        }
        val out = ArrayList<MeteorPass>()
        var t = startMs
        var prevT = t
        var up = (look(t)?.elevation ?: -90.0) >= maskDeg
        var rise = if (up) startMs else 0L
        var maxEl = -90.0
        var maxAt = t
        while (t < endMs) {
            t = minOf(t + stepMs, endMs)
            val el = look(t)?.elevation ?: return out
            if (!up && el >= maskDeg) { rise = crossing(prevT, t); up = true; maxEl = el; maxAt = t }
            else if (up && el < maskDeg) {
                val set = crossing(prevT, t)
                finish(sat, rise, set, maxAt, maxEl, ::look, minPeakDeg)?.let { out += it }
                up = false; maxEl = -90.0
            }
            if (up && el > maxEl) { maxEl = el; maxAt = t }
            prevT = t
        }
        return out
    }

    private fun finish(sat: MeteorSat, rise: Long, set: Long, maxAt: Long, maxEl: Double, look: (Long) -> Look?, minPeak: Double): MeteorPass? {
        var a = maxOf(rise, maxAt - 30_000L)
        var b = minOf(set, maxAt + 30_000L)
        repeat(20) {
            val m1 = a + (b - a) / 3
            val m2 = b - (b - a) / 3
            if ((look(m1)?.elevation ?: -90.0) < (look(m2)?.elevation ?: -90.0)) a = m1 else b = m2
        }
        val best = (a + b) / 2
        val peak = maxOf(maxEl, look(best)?.elevation ?: maxEl)
        if (peak < minPeak) return null
        return MeteorPass(sat, rise, set, best, peak, look(rise)?.azimuth ?: 0.0, look(set)?.azimuth ?: 0.0)
    }
}
