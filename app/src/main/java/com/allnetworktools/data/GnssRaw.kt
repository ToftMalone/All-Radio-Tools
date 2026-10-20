package com.allnetworktools.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/** The receiver clock of one measurement epoch (android.location.GnssClock, copied so the logic is testable). */
data class RawClock(
    val timeNanos: Long,
    val fullBiasNanos: Long?,
    val biasNanos: Double = 0.0,
    val biasUncertaintyNanos: Double? = null,
    val driftNanosPerSecond: Double? = null,
    val driftUncertaintyNanosPerSecond: Double? = null,
    val leapSecond: Int? = null,
    val hardwareDiscontinuityCount: Int = 0,
    /** SystemClock.elapsedRealtimeNanos() at [timeNanos], when the chip gives it. */
    val elapsedRealtimeNanos: Long? = null,
)

/** One satellite signal of an epoch (android.location.GnssMeasurement). */
data class RawMeasurement(
    val constellation: Constellation,
    val svid: Int,
    val carrierHz: Double?,
    val cn0: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long = 0,
    val timeOffsetNanos: Double = 0.0,
    val pseudorangeRateMps: Double = 0.0,
    val adrState: Int = 0,
    val adrMeters: Double = 0.0,
    /** 0 unknown, 1 multipath detected, 2 none. */
    val multipath: Int = 0,
    val codeType: String? = null,
    val agcDb: Double? = null,
)

/** Automatic gain control of one band: it drops when a jammer raises the noise the receiver hears. */
data class RawAgc(val constellation: Constellation, val carrierHz: Double, val levelDb: Double)

data class RawEpoch(val clock: RawClock, val measurements: List<RawMeasurement>, val agc: List<RawAgc>, val receivedAtMs: Long, val receivedAtElapsedNanos: Long)

/** What the phone's GNSS chip says it can do (LocationManager.getGnssCapabilities). */
data class GnssCaps(
    val measurements: Boolean,
    val navigationMessages: Boolean,
    val antennaInfo: Boolean,
    val measurementCorrections: Boolean?,
    val lowPowerMode: Boolean?,
    val satelliteBlocklist: Boolean?,
    val satellitePvt: Boolean?,
    val correlationVectors: Boolean?,
    val multibandTracking: Boolean?,
)

/** Frequency bands as receivers name them, by system: L1 C/A and L5 for GPS, E1 and E5a for Galileo… */
enum class GnssBand(val label: String, val rinex: Char) {
    L1("L1", '1'), L2("L2", '2'), L5("L5", '5'),
    E1("E1", '1'), E5a("E5a", '5'), E5b("E5b", '7'), E6("E6", '6'),
    B1I("B1I", '2'), B1C("B1C", '1'), B2a("B2a", '5'), B2b("B2b", '7'), B3I("B3I", '6'),
    G1("G1", '1'), G2("G2", '2'),
    Unknown("?", '0');

    /** L5-family bands: the second frequency of dual-frequency phones. */
    val isLower get() = this in setOf(L5, E5a, E5b, B2a, B2b, L2, G2, E6, B3I)
}

object GnssRaw {
    const val C = 299_792_458.0
    const val WEEK_NS = 604_800L * 1_000_000_000L
    const val DAY_NS = 86_400L * 1_000_000_000L
    /** 1980-01-06T00:00:00Z, the GPS epoch, in Unix milliseconds. */
    const val GPS_EPOCH_MS = 315_964_800_000L
    const val DEFAULT_LEAP_SECONDS = 18

    // GnssMeasurement state bits.
    const val STATE_CODE_LOCK = 1
    const val STATE_TOW_DECODED = 8
    const val STATE_GLO_TOD_DECODED = 128
    const val STATE_GAL_E1C_2ND_CODE_LOCK = 2048
    const val STATE_TOW_KNOWN = 16384
    const val STATE_GLO_TOD_KNOWN = 32768
    const val ADR_STATE_VALID = 1

    fun band(c: Constellation, carrierHz: Double?): GnssBand {
        val mhz = (carrierHz ?: return if (c == Constellation.Glonass) GnssBand.G1 else bandL1(c)) / 1e6
        fun near(f: Double) = abs(mhz - f) < 3
        return when {
            c == Constellation.Glonass && mhz in 1590.0..1610.0 -> GnssBand.G1
            c == Constellation.Glonass && mhz in 1235.0..1255.0 -> GnssBand.G2
            near(1575.42) -> bandL1(c)
            near(1176.45) -> when (c) { Constellation.Galileo -> GnssBand.E5a; Constellation.BeiDou -> GnssBand.B2a; else -> GnssBand.L5 }
            near(1207.14) -> if (c == Constellation.BeiDou) GnssBand.B2b else GnssBand.E5b
            near(1561.098) -> GnssBand.B1I
            near(1227.60) -> GnssBand.L2
            near(1278.75) -> GnssBand.E6
            near(1268.52) -> GnssBand.B3I
            else -> GnssBand.Unknown
        }
    }

    private fun bandL1(c: Constellation) = when (c) { Constellation.Galileo -> GnssBand.E1; Constellation.BeiDou -> GnssBand.B1C; else -> GnssBand.L1 }

    fun carrierHz(m: RawMeasurement): Double = m.carrierHz ?: when (band(m.constellation, null)) {
        GnssBand.G1 -> 1602e6 // channel unknown: the G1 centre
        else -> 1575.42e6
    }

    fun wavelength(m: RawMeasurement) = C / carrierHz(m)

    /** Whether the satellite's transmit time is known to the full week (or day, for GLONASS): the pseudorange is usable. */
    fun timeKnown(m: RawMeasurement): Boolean = when (m.constellation) {
        Constellation.Glonass -> m.state and (STATE_GLO_TOD_DECODED or STATE_GLO_TOD_KNOWN) != 0
        else -> m.state and (STATE_TOW_DECODED or STATE_TOW_KNOWN) != 0
    }

    /** GPS time of the epoch in nanoseconds since the GPS epoch, or null without a full bias. */
    fun gpsNanos(clock: RawClock): Long? {
        val full = clock.fullBiasNanos ?: return null
        return clock.timeNanos - full - clock.biasNanos.toLong()
    }

    fun leap(clock: RawClock) = clock.leapSecond ?: DEFAULT_LEAP_SECONDS

    /** UTC of the epoch in Unix milliseconds (fractional), from the GNSS clock alone. */
    fun utcMillis(clock: RawClock): Double? {
        val full = clock.fullBiasNanos ?: return null
        val gps = (clock.timeNanos - full).toDouble() - clock.biasNanos
        return GPS_EPOCH_MS + gps / 1e6 - leap(clock) * 1000.0
    }

    /**
     * Pseudorange in metres, the way Google's GNSS measurement tools compute it: receive time in the satellite system's
     * own time scale minus the transmit time the chip decoded, times the speed of light.
     */
    fun pseudorangeM(clock: RawClock, m: RawMeasurement): Double? {
        if (!timeKnown(m)) return null
        val full = clock.fullBiasNanos ?: return null
        val rxGnss = clock.timeNanos.toDouble() + m.timeOffsetNanos - (full.toDouble() + clock.biasNanos)
        val rx = when (m.constellation) {
            Constellation.Glonass -> {
                // GLONASS time is UTC(SU) + 3 h: no leap seconds, day-long.
                val t = Math.floorMod(rx(rxGnss), DAY_NS) + 3 * 3_600_000_000_000L - leap(clock) * 1_000_000_000L
                Math.floorMod(t, DAY_NS).toDouble() + frac(rxGnss)
            }
            Constellation.BeiDou -> Math.floorMod(rx(rxGnss) - 14_000_000_000L, WEEK_NS).toDouble() + frac(rxGnss)
            else -> Math.floorMod(rx(rxGnss), WEEK_NS).toDouble() + frac(rxGnss)
        }
        val period = if (m.constellation == Constellation.Glonass) DAY_NS.toDouble() else WEEK_NS.toDouble()
        var dt = rx - m.receivedSvTimeNanos
        if (dt > period / 2) dt -= period
        if (dt < -period / 2) dt += period
        val pr = dt * 1e-9 * C
        return pr.takeIf { it in 1.5e7..5e7 }
    }

    private fun rx(v: Double) = floor(v).toLong()
    private fun frac(v: Double) = v - floor(v)

    fun dopplerHz(m: RawMeasurement) = -m.pseudorangeRateMps / wavelength(m)

    fun carrierCycles(m: RawMeasurement): Double? = if (m.adrState and ADR_STATE_VALID != 0) m.adrMeters / wavelength(m) else null
}

/**
 * RINEX 3.04 observation file of recorded epochs (pseudorange, carrier phase, Doppler and C/N0 per signal), the format
 * post-processing software (RTKLIB, CSRS-PPP, Emlid Studio…) reads.
 */
object Rinex {
    private fun sys(c: Constellation) = when (c) {
        Constellation.GPS -> 'G'; Constellation.Glonass -> 'R'; Constellation.Galileo -> 'E'
        Constellation.BeiDou -> 'C'; Constellation.QZSS -> 'J'; Constellation.Other -> 'S'
    }

    private fun defaultCode(b: GnssBand) = when (b) {
        GnssBand.L5, GnssBand.E5a -> 'Q'
        GnssBand.B1I, GnssBand.B3I -> 'I'
        GnssBand.B1C, GnssBand.B2a -> 'P'
        GnssBand.E5b -> 'Q'
        else -> 'C'
    }

    /** "1C", "5Q", "2I"…: the band digit and tracking code of a signal. */
    fun signal(m: RawMeasurement): String {
        val b = GnssRaw.band(m.constellation, m.carrierHz)
        return "${b.rinex}${m.codeType?.firstOrNull() ?: defaultCode(b)}"
    }

    private fun label(text: String, label: String) = text.padEnd(60).take(60) + label + "\n"

    private fun calendar(gpsNs: Long): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = GnssRaw.GPS_EPOCH_MS + Math.floorDiv(gpsNs, 1_000_000L)
    }

    private fun obs(v: Double?) = if (v == null || v.isNaN()) "".padStart(16) else String.format(Locale.ROOT, "%14.3f  ", v)

    fun format(epochs: List<RawEpoch>, program: String = "AllRadioTools", nowMs: Long = System.currentTimeMillis()): String {
        val usable = epochs.filter { GnssRaw.gpsNanos(it.clock) != null }
        // Observation types per system: every signal seen, four observables each.
        val types = sortedMapOf<Char, MutableSet<String>>()
        usable.forEach { e -> e.measurements.forEach { m -> types.getOrPut(sys(m.constellation)) { sortedSetOf() } += signal(m) } }
        val sb = StringBuilder()
        sb.append(label("     3.04           OBSERVATION DATA    M: Mixed", "RINEX VERSION / TYPE"))
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
        sb.append(label(String.format(Locale.ROOT, "%-20s%-20s%04d%02d%02d %02d%02d%02d UTC", program, "", now[Calendar.YEAR], now[Calendar.MONTH] + 1, now[Calendar.DAY_OF_MONTH], now[Calendar.HOUR_OF_DAY], now[Calendar.MINUTE], now[Calendar.SECOND]), "PGM / RUN BY / DATE"))
        sb.append(label("Android", "MARKER NAME"))
        sb.append(label("", "OBSERVER / AGENCY"))
        sb.append(label(String.format(Locale.ROOT, "%-20s%-20s%-20s", "", "Android", ""), "REC # / TYPE / VERS"))
        sb.append(label(String.format(Locale.ROOT, "%-20s%-20s", "", "Phone"), "ANT # / TYPE"))
        sb.append(label(String.format(Locale.ROOT, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "APPROX POSITION XYZ"))
        sb.append(label(String.format(Locale.ROOT, "%14.4f%14.4f%14.4f", 0.0, 0.0, 0.0), "ANTENNA: DELTA H/E/N"))
        types.forEach { (s, sigs) ->
            val codes = sigs.flatMap { listOf("C$it", "L$it", "D$it", "S$it") }
            codes.chunked(13).forEachIndexed { i, chunk ->
                val head = if (i == 0) String.format(Locale.ROOT, "%c  %3d", s, codes.size) else "      "
                sb.append(label(head + chunk.joinToString("") { " $it" }, "SYS / # / OBS TYPES"))
            }
        }
        usable.firstOrNull()?.let { e ->
            val c = calendar(GnssRaw.gpsNanos(e.clock)!!)
            val sec = c[Calendar.SECOND] + Math.floorMod(GnssRaw.gpsNanos(e.clock)!!, 1_000_000_000L) / 1e9
            sb.append(label(String.format(Locale.ROOT, "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS", c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH], c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], sec), "TIME OF FIRST OBS"))
        }
        sb.append(label("", "END OF HEADER"))
        usable.forEach { e ->
            val gps = GnssRaw.gpsNanos(e.clock)!!
            val c = calendar(gps)
            val sec = c[Calendar.SECOND] + Math.floorMod(gps, 1_000_000_000L) / 1e9
            val bySat = e.measurements.groupBy { "${sys(it.constellation)}${String.format(Locale.ROOT, "%02d", it.svid)}" }.toSortedMap()
            sb.append(String.format(Locale.ROOT, "> %04d %02d %02d %02d %02d%11.7f  0%3d\n", c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH], c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], sec, bySat.size))
            bySat.forEach { (sat, ms) ->
                val sigs = types.getValue(sat[0])
                sb.append(sat)
                sigs.forEach { sig ->
                    val m = ms.firstOrNull { signal(it) == sig }
                    if (m == null) {
                        repeat(4) { sb.append(obs(null)) }
                    } else {
                        sb.append(obs(GnssRaw.pseudorangeM(e.clock, m)))
                        sb.append(obs(GnssRaw.carrierCycles(m)))
                        sb.append(obs(GnssRaw.dopplerHz(m)))
                        sb.append(obs(m.cn0))
                    }
                }
                sb.setLength(sb.trimEnd().length)
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
