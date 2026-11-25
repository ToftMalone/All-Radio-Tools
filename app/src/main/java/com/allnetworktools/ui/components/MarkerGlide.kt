package com.allnetworktools.ui.components

import org.osmdroid.util.GeoPoint
import kotlin.math.abs

/**
 * Markers that glide on a map: when a marker's position changes, it moves there over [DURATION_MS] instead of jumping
 * (an aircraft's, a ship's or a balloon's new fix). A jump of more than [SNAP_DEG] (a wrong fix, a new start) snaps.
 * Call [at] for each marker while drawing, then ask for another frame while [moving].
 */
class MarkerGlide<K : Any> {
    private class State(var fromLat: Double, var fromLon: Double, var toLat: Double, var toLon: Double, var startNs: Long)

    private val states = HashMap<K, State>()
    private var used = HashSet<K>()
    /** Some marker is still on its way: draw again. */
    var moving = false
        private set

    /** Starts a frame. */
    fun begin() { moving = false; used.clear() }

    fun at(key: K, lat: Double, lon: Double, nowNs: Long = System.nanoTime()): GeoPoint {
        used += key
        val s = states[key] ?: State(lat, lon, lat, lon, nowNs).also { states[key] = it }
        if (lat != s.toLat || lon != s.toLon) {
            val (cLat, cLon) = position(s, nowNs)
            if (abs(lat - cLat) > SNAP_DEG || abs(lon - cLon) > SNAP_DEG) { s.fromLat = lat; s.fromLon = lon }
            else { s.fromLat = cLat; s.fromLon = cLon }
            s.toLat = lat; s.toLon = lon; s.startNs = nowNs
        }
        val (la, lo) = position(s, nowNs)
        if (la != s.toLat || lo != s.toLon) moving = true
        return GeoPoint(la, lo)
    }

    /** Ends a frame: markers no longer drawn are forgotten. */
    fun end() { states.keys.retainAll(used) }

    private fun position(s: State, nowNs: Long): Pair<Double, Double> {
        val t = ((nowNs - s.startNs) / 1e6 / DURATION_MS).coerceIn(0.0, 1.0)
        if (t >= 1.0) return s.toLat to s.toLon
        // Ease out: quick start, gentle arrival.
        val e = 1 - (1 - t) * (1 - t) * (1 - t)
        return (s.fromLat + (s.toLat - s.fromLat) * e) to (s.fromLon + (s.toLon - s.fromLon) * e)
    }

    companion object {
        const val DURATION_MS = 900.0
        const val SNAP_DEG = 0.5
    }
}
