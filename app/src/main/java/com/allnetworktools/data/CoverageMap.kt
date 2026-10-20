package com.allnetworktools.data

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One measurement taken while walking: where the phone was, in metres on the floor (x to the right, z towards the
 * viewer when the map starts, as AR tracking reports it), and the Wi-Fi signal there.
 */
data class CoverageSample(val x: Float, val z: Float, val rssi: Int, val bssid: String?, val atMs: Long, val floorY: Float = 0f)

/** A square of floor on the coverage map and its estimated signal. */
data class CoverageCell(val ix: Int, val iz: Int, val rssi: Float)

data class CoverageStats(
    val samples: Int,
    val distanceM: Float,
    val areaM2: Float,
    /** Share of the mapped floor at -67 dBm or better (calls and streaming comfortable). */
    val goodShare: Float,
    val minRssi: Int?,
    val maxRssi: Int?,
    val meanRssi: Float?,
    val accessPoints: Int,
)

object CoverageMap {
    /** Map squares are 50 cm wide. */
    const val CELL_M = 0.5f
    /** A square is mapped when a measurement is at most this far: the walked area, with a margin of an arm's length. */
    const val REACH_M = 1.25f
    /** Measurements further than this do not weigh on a square. */
    private const val INFLUENCE_M = 3f
    /** Walking this far takes a new measurement. */
    const val STEP_M = 0.4f

    /** -90 dBm and below is red, -50 dBm and above green. */
    fun level(rssi: Float): Float = ((rssi + 90f) / 40f).coerceIn(0f, 1f)

    fun shouldSample(last: CoverageSample?, x: Float, z: Float): Boolean =
        last == null || hypot(x - last.x, z - last.z) >= STEP_M

    /** Inverse-square-distance weighting of nearby measurements at (x, z), or null away from where anyone walked. */
    fun estimate(samples: List<CoverageSample>, x: Float, z: Float): Float? {
        var num = 0.0
        var den = 0.0
        var nearest = Float.MAX_VALUE
        for (s in samples) {
            val d = hypot(s.x - x, s.z - z)
            if (d < nearest) nearest = d
            if (d > INFLUENCE_M) continue
            val w = 1.0 / (d * d + 0.05)
            num += w * s.rssi
            den += w
        }
        return if (nearest > REACH_M || den == 0.0) null else (num / den).toFloat()
    }

    fun cells(samples: List<CoverageSample>): List<CoverageCell> {
        if (samples.isEmpty()) return emptyList()
        val x0 = floor((samples.minOf { it.x } - REACH_M) / CELL_M).toInt()
        val x1 = floor((samples.maxOf { it.x } + REACH_M) / CELL_M).toInt()
        val z0 = floor((samples.minOf { it.z } - REACH_M) / CELL_M).toInt()
        val z1 = floor((samples.maxOf { it.z } + REACH_M) / CELL_M).toInt()
        val out = ArrayList<CoverageCell>()
        for (i in x0..x1) for (j in z0..z1) {
            val v = estimate(samples, (i + 0.5f) * CELL_M, (j + 0.5f) * CELL_M) ?: continue
            out += CoverageCell(i, j, v)
        }
        return out
    }

    fun stats(samples: List<CoverageSample>, cells: List<CoverageCell> = cells(samples)): CoverageStats = CoverageStats(
        samples = samples.size,
        distanceM = samples.zipWithNext { a, b -> hypot(b.x - a.x, b.z - a.z) }.sum(),
        areaM2 = cells.size * CELL_M * CELL_M,
        goodShare = if (cells.isEmpty()) 0f else cells.count { it.rssi >= -67f }.toFloat() / cells.size,
        minRssi = samples.minOfOrNull { it.rssi },
        maxRssi = samples.maxOfOrNull { it.rssi },
        meanRssi = if (samples.isEmpty()) null else samples.map { it.rssi }.average().toFloat(),
        accessPoints = samples.mapNotNull { it.bssid }.distinct().size,
    )

    fun csv(samples: List<CoverageSample>): String = buildString {
        append("horodatage_ms,x_m,z_m,rssi_dbm,bssid\r\n")
        samples.forEach { append("${it.atMs},${"%.2f".format(java.util.Locale.ROOT, it.x)},${"%.2f".format(java.util.Locale.ROOT, it.z)},${it.rssi},${it.bssid ?: ""}\r\n") }
    }
}

/**
 * Step detection from the accelerometer alone (no activity-recognition permission): the acceleration's magnitude,
 * smoothed, crossing above its slow average by [THRESHOLD] then back counts one step, at most one every 280 ms.
 */
class StepDetector {
    private var fast = Float.NaN
    private var slow = Float.NaN
    private var high = false
    private var lastStepNs = 0L

    /** [magnitude] in m/s² including gravity; true when this sample completes a step. */
    fun feed(timeNs: Long, magnitude: Float): Boolean {
        if (fast.isNaN()) { fast = magnitude; slow = magnitude; return false }
        fast += 0.35f * (magnitude - fast)
        slow += 0.02f * (magnitude - slow)
        val d = fast - slow
        if (!high && d > THRESHOLD) high = true
        if (high && d < 0f) {
            high = false
            if (timeNs - lastStepNs > 280_000_000L) {
                lastStepNs = timeNs
                return true
            }
        }
        return false
    }

    companion object { const val THRESHOLD = 1.1f }
}

/** Dead reckoning: each step moves [stepM] metres along the heading (radians, clockwise from the map's up). */
class StepWalker(private val stepM: Float = 0.7f) {
    var x = 0f
        private set
    var z = 0f
        private set

    fun step(headingRad: Float) {
        x += stepM * sin(headingRad)
        z -= stepM * cos(headingRad)
    }
}

fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
