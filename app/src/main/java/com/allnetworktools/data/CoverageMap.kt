package com.allnetworktools.data

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import com.allnetworktools.R
import com.allnetworktools.util.S

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

/** WiFiman's signal scale: red at -80 dBm through orange, yellow and green to teal at -30 dBm. */
object CoveragePalette {
    val stops = listOf(
        -80 to 0xFFE53935.toInt(),
        -70 to 0xFFFB8C00.toInt(),
        -60 to 0xFFFDD835.toInt(),
        -50 to 0xFFC0CA33.toInt(),
        -40 to 0xFF43A047.toInt(),
        -30 to 0xFF26A69A.toInt(),
    )

    fun argb(rssi: Float): Int {
        if (rssi <= stops.first().first) return stops.first().second
        if (rssi >= stops.last().first) return stops.last().second
        val i = stops.indexOfLast { it.first <= rssi }
        val (r0, c0) = stops[i]
        val (r1, c1) = stops[i + 1]
        val t = (rssi - r0) / (r1 - r0)
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(c0, s) + (ch(c1, s) - ch(c0, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** French wording of the quality, as the bottom bar shows it. */
    fun quality(rssi: Int) = when {
        rssi >= -50 -> S(R.string.excellent)
        rssi >= -60 -> S(R.string.very_good)
        rssi >= -67 -> "Bon"
        rssi >= -75 -> S(R.string.fair)
        else -> S(R.string.weak)
    }
}

/**
 * The floor painted while walking, in 10 cm tiles: every position paints the tiles within [BRUSH_M] with the signal
 * measured there (averaged when walked over again), each tile at the floor height first seen under it.
 */
class PaintedFloor {
    private class Tile(var sum: Float, var count: Int, val y: Float)
    private val tiles = LinkedHashMap<Long, Tile>()

    val size get() = tiles.size

    fun clear() = tiles.clear()

    fun paint(x: Float, z: Float, floorY: Float, rssi: Int) {
        val r = (BRUSH_M / TILE_M).toInt() + 1
        val cx = floor(x / TILE_M).toInt()
        val cz = floor(z / TILE_M).toInt()
        for (i in cx - r..cx + r) for (j in cz - r..cz + r) {
            if (hypot((i + 0.5f) * TILE_M - x, (j + 0.5f) * TILE_M - z) > BRUSH_M) continue
            val t = tiles.getOrPut(key(i, j)) { Tile(0f, 0, floorY) }
            t.sum += rssi; t.count++
        }
    }

    fun rssiAt(i: Int, j: Int): Float? = tiles[key(i, j)]?.let { it.sum / it.count }

    /** Every tile as (column, row, signal). */
    fun tiles(): List<Triple<Int, Int, Float>> = tiles.map { (k, t) -> Triple((k shr 32).toInt(), k.toInt(), t.sum / t.count) }

    /** Two triangles per tile, slightly inset so the grid shows: x, y, z, r, g, b, a for each vertex. */
    fun vertices(alpha: Float = 0.82f): FloatArray {
        val out = FloatArray(tiles.size * 6 * 7)
        var k = 0
        val inset = TILE_M * 0.09f
        for ((key, t) in tiles) {
            val i = (key shr 32).toInt()
            val j = key.toInt()
            val c = CoveragePalette.argb(t.sum / t.count)
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val x0 = i * TILE_M + inset
            val x1 = (i + 1) * TILE_M - inset
            val z0 = j * TILE_M + inset
            val z1 = (j + 1) * TILE_M - inset
            for ((vx, vz) in arrayOf(x0 to z0, x1 to z0, x1 to z1, x0 to z0, x1 to z1, x0 to z1)) {
                out[k++] = vx; out[k++] = t.y; out[k++] = vz
                out[k++] = r; out[k++] = g; out[k++] = b; out[k++] = alpha
            }
        }
        return out
    }

    private fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    companion object {
        const val TILE_M = 0.1f
        const val BRUSH_M = 0.3f
    }
}
