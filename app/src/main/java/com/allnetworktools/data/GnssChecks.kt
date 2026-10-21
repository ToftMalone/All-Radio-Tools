package com.allnetworktools.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class Verdict { Unknown, Ok, Warn, Alert }

/** One clue of the jamming / spoofing analysis, as shown in the list. */
data class IntegrityCheck(val title: String, val verdict: Verdict, val detail: String)

/** One second of what the receiver sees, for the integrity analysis. */
data class IntegritySample(
    val timeMs: Long,
    /** Satellites above the horizon: tracked ones with C/N0 > 0, known but untracked ones with 0. */
    val sats: List<Satellite>,
    val agc: List<RawAgc>,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val fixElapsedMs: Long?,
    val mock: Boolean,
    /** GNSS UTC minus the phone's clock, in ms, when a fix gives it. */
    val timeOffsetMs: Double?,
    val networkLat: Double?,
    val networkLon: Double?,
    val networkAccuracyM: Float?,
)

data class IntegrityReport(val jamming: Verdict, val spoofing: Verdict, val checks: List<IntegrityCheck>, val baselineCn0: Double?, val cn0: Double?)

/**
 * Jamming and spoofing clues, combined the way receiver-integrity monitors do: a jammer raises the noise floor (AGC
 * falls, every C/N0 falls together, satellites stay listed but untracked); a spoofer produces signals too alike (C/N0
 * all equal, unrelated to elevation), an impossible jump, a position far from the network's, or a shifted time.
 */
class GnssIntegrity {
    private val history = ArrayDeque<IntegritySample>()
    private var baselineCn0: Double? = null
    private val agcBaseline = HashMap<String, Double>()
    private var lastFix: IntegritySample? = null
    private var maxJumpMps = 0.0

    fun reset() { history.clear(); baselineCn0 = null; agcBaseline.clear(); lastFix = null; maxJumpMps = 0.0 }

    fun feed(s: IntegritySample): IntegrityReport {
        history.addLast(s)
        while (history.size > 120) history.removeFirst()
        val strong = strongMean(s.sats)
        // The baseline follows the signal slowly upwards and only very slowly down, so a sudden drop stands out.
        if (strong != null) baselineCn0 = baselineCn0?.let { if (strong > it) it + 0.2 * (strong - it) else it + 0.01 * (strong - it) } ?: strong
        s.agc.forEach { a ->
            val k = "${a.constellation}-${(a.carrierHz / 1e6).toInt()}"
            agcBaseline[k] = agcBaseline[k]?.let { if (a.levelDb > it) it + 0.2 * (a.levelDb - it) else it + 0.005 * (a.levelDb - it) } ?: a.levelDb
        }
        if (s.lat != null && s.lon != null && s.fixElapsedMs != null) {
            lastFix?.let { p ->
                val dt = (s.fixElapsedMs - p.fixElapsedMs!!) / 1000.0
                if (dt > 0.5) maxJumpMps = maxOf(maxJumpMps, haversine(p.lat!!, p.lon!!, s.lat, s.lon) / dt)
            }
            lastFix = s
        }
        return report(s, strong)
    }

    private fun report(s: IntegritySample, strong: Double?): IntegrityReport {
        val checks = mutableListOf<IntegrityCheck>()
        val tracked = s.sats.filter { it.cn0 > 0 }
        val listed = s.sats.filter { it.elevation >= 15 }
        // 1. C/N0 drop against this session's baseline.
        val base = baselineCn0
        val drop = if (strong != null && base != null) base - strong else null
        checks += IntegrityCheck(
            S(R.string.signal_level),
            when {
                strong == null -> if (listed.size >= 6) Verdict.Alert else Verdict.Unknown
                drop!! >= 10 -> Verdict.Alert
                drop >= 6 -> Verdict.Warn
                else -> Verdict.Ok
            },
            when {
                strong == null && listed.size >= 6 -> S(R.string.satellites_above_the_horizon_but_none, listed.size)
                strong == null -> S(R.string.no_satellite_received_yet)
                else -> S(R.string.average_of_the_4_strongest_db, fmt1(strong), fmt1(base!!))
            },
        )
        // 2. Automatic gain control.
        val agcDrops = s.agc.mapNotNull { a -> agcBaseline["${a.constellation}-${(a.carrierHz / 1e6).toInt()}"]?.let { it - a.levelDb } }
        val agcDrop = agcDrops.maxOrNull()
        checks += IntegrityCheck(
            S(R.string.input_gain_agc),
            when {
                agcDrop == null -> Verdict.Unknown
                agcDrop >= 8 -> Verdict.Alert
                agcDrop >= 4 -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (agcDrop == null) S(R.string.this_phone_does_not_publish_its) else S(R.string.down_db_since_the_start_a, fmt1(maxOf(0.0, agcDrop))),
        )
        // 3. Share of the sky that should be heard but is not.
        val lost = if (listed.isEmpty()) null else listed.count { it.cn0 <= 0f }.toDouble() / listed.size
        checks += IntegrityCheck(
            S(R.string.lost_satellites),
            when {
                lost == null || listed.size < 4 -> Verdict.Unknown
                lost >= 0.8 -> Verdict.Alert
                lost >= 0.5 -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (lost == null) S(R.string.no_satellite_above_15_yet) else S(R.string.of_high_satellites_are_not_received, listed.count { it.cn0 <= 0f }, listed.size),
        )
        // 4. Signals too alike: a single transmitter on the ground.
        val spread = stdDev(tracked.map { it.cn0.toDouble() })
        val corr = correlation(tracked.map { it.elevation.toDouble() }, tracked.map { it.cn0.toDouble() })
        checks += IntegrityCheck(
            S(R.string.signals_too_alike),
            when {
                tracked.size < 6 -> Verdict.Unknown
                spread!! < 1.5 && (strong ?: 0.0) > 38 -> Verdict.Alert
                spread < 2.5 && (corr ?: 1.0) < 0.1 -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (tracked.size < 6) S(R.string.at_least_6_received_satellites_are) else
                S(R.string.spread_between_satellites_db_link_with, fmt1(spread!!), corr?.let { fmt2(it) } ?: "—"),
        )
        // 5. Impossible jumps.
        checks += IntegrityCheck(
            S(R.string.position_jumps),
            when {
                lastFix == null -> Verdict.Unknown
                maxJumpMps > 340 -> Verdict.Alert
                maxJumpMps > 70 -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (lastFix == null) S(R.string.no_position_yet) else S(R.string.highest_apparent_speed_km_h, fmt1(maxJumpMps * 3.6)),
        )
        // 6. Agreement with the network position.
        val netDist = if (s.lat != null && s.lon != null && s.networkLat != null && s.networkLon != null) haversine(s.lat, s.lon, s.networkLat, s.networkLon) else null
        val tol = maxOf(1000.0, 3.0 * (s.networkAccuracyM ?: 0f))
        checks += IntegrityCheck(
            S(R.string.agreement_with_the_network),
            when {
                netDist == null -> Verdict.Unknown
                netDist > 5 * tol -> Verdict.Alert
                netDist > tol -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (netDist == null) S(R.string.a_gnss_position_and_a_network) else S(R.string.the_network_position_is_away, if (netDist >= 1000) fmt1(netDist / 1000) + " km" else "${netDist.toInt()} m"),
        )
        // 7. GNSS time against the phone's network-synchronised clock.
        val dt = s.timeOffsetMs
        checks += IntegrityCheck(
            S(R.string.satellite_time),
            when {
                dt == null -> Verdict.Unknown
                abs(dt) > 10_000 -> Verdict.Alert
                abs(dt) > 2_000 -> Verdict.Warn
                else -> Verdict.Ok
            },
            if (dt == null) S(R.string.no_gnss_time_yet) else S(R.string.offset_from_the_phone_s_clock, fmt1(dt / 1000)),
        )
        // 8. Location simulated by an app.
        checks += IntegrityCheck(
            S(R.string.simulated_position),
            if (s.mock) Verdict.Alert else if (s.lat == null) Verdict.Unknown else Verdict.Ok,
            if (s.mock) S(R.string.android_reports_a_mock_location_supplied) else S(R.string.position_from_the_gnss_chip),
        )
        val jam = worst(checks.take(3))
        val spoof = worst(checks.drop(3))
        return IntegrityReport(jam, spoof, checks, baselineCn0, strong)
    }

    private fun worst(list: List<IntegrityCheck>): Verdict {
        val alerts = list.count { it.verdict == Verdict.Alert }
        val warns = list.count { it.verdict == Verdict.Warn }
        return when {
            alerts >= 2 || (alerts == 1 && warns >= 1) -> Verdict.Alert
            alerts == 1 || warns >= 1 -> Verdict.Warn
            list.any { it.verdict == Verdict.Ok } -> Verdict.Ok
            else -> Verdict.Unknown
        }
    }

    companion object {
        fun strongMean(sats: List<Satellite>): Double? =
            sats.filter { it.cn0 > 0 }.map { it.cn0.toDouble() }.sortedDescending().take(4).takeIf { it.isNotEmpty() }?.average()

        fun stdDev(v: List<Double>): Double? {
            if (v.size < 2) return null
            val m = v.average()
            return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        }

        fun correlation(x: List<Double>, y: List<Double>): Double? {
            if (x.size < 3) return null
            val mx = x.average(); val my = y.average()
            val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
            val sxx = x.sumOf { (it - mx) * (it - mx) }
            val syy = y.sumOf { (it - my) * (it - my) }
            return if (sxx == 0.0 || syy == 0.0) 0.0 else sxy / sqrt(sxx * syy)
        }

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}

private fun fmt1(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f", v)
private fun fmt2(v: Double) = String.format(java.util.Locale.getDefault(), "%.2f", v)

/** Compass check: the GNSS course while walking or driving against the magnetic heading corrected by the declination. */
class CompassCheck {
    data class Pair(val gnss: Float, val compass: Float, val diff: Float)
    val pairs = ArrayList<Pair>()

    fun reset() = pairs.clear()

    /** [compassTrue] is the magnetometer heading plus the local declination, in degrees. */
    fun feed(gnssBearing: Float, speedMps: Float, bearingAccuracyDeg: Float?, compassTrue: Float): Pair? {
        if (speedMps < 1.2f || (bearingAccuracyDeg != null && bearingAccuracyDeg > 20f)) return null
        val p = Pair(gnssBearing, compassTrue, wrap(compassTrue - gnssBearing))
        pairs += p
        if (pairs.size > 600) pairs.removeAt(0)
        return p
    }

    /** Circular mean of the differences (degrees) and their spread. */
    fun offset(): kotlin.Pair<Float, Float>? {
        if (pairs.size < 5) return null
        val s = pairs.sumOf { sin(Math.toRadians(it.diff.toDouble())) }
        val c = pairs.sumOf { cos(Math.toRadians(it.diff.toDouble())) }
        val mean = Math.toDegrees(kotlin.math.atan2(s, c)).toFloat()
        val spread = sqrt(pairs.map { wrap(it.diff - mean).toDouble().let { d -> d * d } }.average()).toFloat()
        return mean to spread
    }

    companion object {
        /** Angle folded into −180…180. */
        fun wrap(a: Float): Float = ((a % 360 + 540) % 360) - 180
    }
}

/** Which parts of the sky are blocked, from where satellites are listed but weak or untracked. */
class SkyMask {
    /** 12 azimuth sectors × 3 elevation rings (10–30°, 30–60°, 60–90°). */
    class Cell(var clear: Int = 0, var blocked: Int = 0)
    val cells = Array(3) { Array(12) { Cell() } }
    var observations = 0
        private set

    fun reset() { cells.forEach { r -> r.forEach { it.clear = 0; it.blocked = 0 } }; observations = 0 }

    fun ring(el: Float) = when { el < 10 -> -1; el < 30 -> 0; el < 60 -> 1; else -> 2 }

    fun feed(sats: List<Satellite>) {
        sats.forEach { s ->
            val r = ring(s.elevation)
            if (r < 0) return@forEach
            val a = (((s.azimuth % 360 + 360) % 360) / 30).toInt().coerceIn(0, 11)
            // Expected C/N0 rises with elevation; 10 dB under it (or untracked) counts as blocked.
            val expected = 32f + 12f * (s.elevation / 90f)
            if (s.cn0 <= 0f || s.cn0 < expected - 10f) cells[r][a].blocked++ else cells[r][a].clear++
            observations++
        }
    }

    /** null: not seen yet; 0 clear … 1 blocked. */
    fun blockedShare(ring: Int, sector: Int): Float? {
        val c = cells[ring][sector]
        val n = c.clear + c.blocked
        return if (n < 3) null else c.blocked.toFloat() / n
    }

    /** Share of the observed sky that is open, weighted by each ring's solid angle. */
    fun openSky(): Float? {
        val weights = floatArrayOf(0.326f, 0.366f, 0.134f) // sin(30)-sin(10), sin(60)-sin(30), 1-sin(60)
        var open = 0f; var total = 0f
        for (r in 0 until 3) for (a in 0 until 12) blockedShare(r, a)?.let { open += weights[r] * (1 - it); total += weights[r] }
        return if (total == 0f) null else open / total
    }

    /** The lowest clear elevation for each sector: what a horizon profile of the obstacles looks like. */
    fun clearDirections(): List<Int> = (0 until 12).filter { a -> (0 until 3).all { r -> (blockedShare(r, a) ?: 0f) < 0.5f } && blockedShare(0, a) != null }
}
