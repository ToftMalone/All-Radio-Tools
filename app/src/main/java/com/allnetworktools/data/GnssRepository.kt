package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.os.Build
import android.os.SystemClock
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class Constellation(val label: String, val short: String, val prefix: String) {
    GPS("GPS", "GPS", "G"),
    Galileo("Galileo", "GAL", "E"),
    Glonass("GLONASS", "GLO", "R"),
    BeiDou("BeiDou", "BDS", "C"),
    QZSS("QZSS", "QZS", "J"),
    Other(S(R.string.other), "AUT", "S"),
}

private fun constellationOf(type: Int) = when (type) {
    GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
    GnssStatus.CONSTELLATION_GALILEO -> Constellation.Galileo
    GnssStatus.CONSTELLATION_GLONASS -> Constellation.Glonass
    GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BeiDou
    GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
    else -> Constellation.Other
}

data class Satellite(
    val constellation: Constellation,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val used: Boolean,
    val carrierMhz: Float?,
) {
    val id: String get() = constellation.prefix + svid
}

enum class FixType(val label: String) { None(S(R.string.no_fix)), Fix2D("Fix 2D"), Fix3D("Fix 3D") }

data class GnssState(
    val satellites: List<Satellite> = emptyList(),
    val location: Location? = null,
    val fix: FixType = FixType.None,
    val hdop: Float? = null,
    val ttffMs: Int? = null,
) {
    val visible get() = satellites.filter { it.elevation > 0f || it.cn0 > 0f }
    val used get() = satellites.filter { it.used }
    val constellationCount get() = visible.map { it.constellation }.filter { it != Constellation.Other }.distinct().size
}

/** The three positions Android can give at once: satellites, network (Wi-Fi + cell towers) and fused. */
data class PositionSet(val gnss: Location? = null, val network: Location? = null, val fused: Location? = null)

@SuppressLint("MissingPermission")
open class GnssRepository(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    open fun lastKnownLocation(): Location? = runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm?.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    /** Satellites, GPS fixes and NMEA-derived HDOP / fix type. Requires ACCESS_FINE_LOCATION. */
    open fun status(refreshMs: Long): Flow<GnssState> = callbackFlow {
        val mgr = lm ?: run { trySend(GnssState()); awaitClose { }; return@callbackFlow }
        var state = GnssState(location = lastKnownLocation()?.takeIf { it.provider == LocationManager.GPS_PROVIDER })
        fun update(block: (GnssState) -> GnssState) {
            state = block(state); trySend(state)
        }
        val statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = (0 until status.satelliteCount).map { i ->
                    Satellite(
                        constellation = constellationOf(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        cn0 = status.getCn0DbHz(i),
                        elevation = status.getElevationDegrees(i),
                        azimuth = status.getAzimuthDegrees(i),
                        used = status.usedInFix(i),
                        carrierMhz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i) / 1e6f else null,
                    )
                }.distinctBy { it.id }
                update { it.copy(satellites = sats) }
            }

            override fun onFirstFix(ttffMillis: Int) = update { it.copy(ttffMs = ttffMillis) }

            override fun onStopped() = update { it.copy(fix = FixType.None) }
        }
        val locationListener = LocationListener { loc ->
            update { s ->
                val fix = if (s.fix != FixType.None) s.fix else if (loc.hasAltitude()) FixType.Fix3D else FixType.Fix2D
                s.copy(location = loc, fix = fix)
            }
        }
        val nmea = OnNmeaMessageListener { msg, _ -> parseNmea(msg)?.let { (fix, hdop) -> update { s -> s.copy(fix = fix ?: s.fix, hdop = hdop ?: s.hdop) } } }
        runCatching {
            mgr.registerGnssStatusCallback(context.mainExecutor, statusCb)
            mgr.addNmeaListener(context.mainExecutor, nmea)
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, refreshMs, 0f, locationListener, context.mainLooper)
        }
        trySend(state)
        awaitClose {
            mgr.unregisterGnssStatusCallback(statusCb)
            mgr.removeNmeaListener(nmea)
            mgr.removeUpdates(locationListener)
        }
    }

    /**
     * Follows the GPS, network and fused providers together. The network provider is computed by
     * the system from the visible Wi-Fi access points and the cell towers; Bluetooth is not a
     * position source Android exposes to apps.
     */
    open fun positions(refreshMs: Long): Flow<PositionSet> = callbackFlow {
        val mgr = lm ?: run { trySend(PositionSet()); awaitClose { }; return@callbackFlow }
        fun last(p: String) = runCatching { mgr.getLastKnownLocation(p) }.getOrNull()
        var set = PositionSet(last(LocationManager.GPS_PROVIDER), last(LocationManager.NETWORK_PROVIDER), last(LocationManager.FUSED_PROVIDER))
        val listeners = mutableListOf<LocationListener>()
        fun follow(provider: String, apply: (PositionSet, Location) -> PositionSet) {
            if (provider !in mgr.allProviders) return
            val l = LocationListener { loc -> set = apply(set, loc); trySend(set) }
            listeners += l
            runCatching { mgr.requestLocationUpdates(provider, refreshMs, 0f, l, context.mainLooper) }
        }
        follow(LocationManager.GPS_PROVIDER) { s, l -> s.copy(gnss = l) }
        follow(LocationManager.NETWORK_PROVIDER) { s, l -> s.copy(network = l) }
        follow(LocationManager.FUSED_PROVIDER) { s, l -> s.copy(fused = l) }
        trySend(set)
        awaitClose { listeners.forEach { mgr.removeUpdates(it) } }
    }

    /** What the GNSS chip supports, or null when Android does not say. */
    open fun capabilities(): GnssCaps? = runCatching {
        val c = lm!!.gnssCapabilities
        val u = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        GnssCaps(
            measurements = c.hasMeasurements(),
            navigationMessages = c.hasNavigationMessages(),
            antennaInfo = c.hasAntennaInfo(),
            measurementCorrections = if (u) c.hasMeasurementCorrections() else null,
            lowPowerMode = if (u) c.hasLowPowerMode() else null,
            satelliteBlocklist = if (u) c.hasSatelliteBlocklist() else null,
            satellitePvt = if (u) c.hasSatellitePvt() else null,
            correlationVectors = if (u) c.hasMeasurementCorrelationVectors() else null,
            multibandTracking = if (u) c.hasPowerMultibandTracking() else null,
        )
    }.getOrNull()

    /**
     * Raw measurements, one epoch a second, with full tracking (every satellite, every band, no duty cycling). The GPS
     * provider is kept running meanwhile: the chip only measures while a location request is active.
     */
    open fun raw(): Flow<RawEpoch> = callbackFlow {
        val mgr = lm ?: run { awaitClose { }; return@callbackFlow }
        val cb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                trySend(epochOf(event))
            }
        }
        val keepAlive = LocationListener { }
        runCatching {
            mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, keepAlive, context.mainLooper)
            mgr.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), context.mainExecutor, cb)
        }
        awaitClose {
            mgr.unregisterGnssMeasurementsCallback(cb)
            mgr.removeUpdates(keepAlive)
        }
    }

    @Suppress("DEPRECATION")
    private fun epochOf(e: GnssMeasurementsEvent): RawEpoch {
        val c = e.clock
        val clock = RawClock(
            timeNanos = c.timeNanos,
            fullBiasNanos = if (c.hasFullBiasNanos()) c.fullBiasNanos else null,
            biasNanos = if (c.hasBiasNanos()) c.biasNanos else 0.0,
            biasUncertaintyNanos = if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else null,
            driftNanosPerSecond = if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond else null,
            driftUncertaintyNanosPerSecond = if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond else null,
            leapSecond = if (c.hasLeapSecond()) c.leapSecond else null,
            hardwareDiscontinuityCount = c.hardwareClockDiscontinuityCount,
            elapsedRealtimeNanos = if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null,
        )
        val ms = e.measurements.map { m ->
            RawMeasurement(
                constellation = constellationOf(m.constellationType),
                svid = m.svid,
                carrierHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
                cn0 = m.cn0DbHz,
                state = m.state,
                receivedSvTimeNanos = m.receivedSvTimeNanos,
                receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos,
                timeOffsetNanos = m.timeOffsetNanos,
                pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
                adrState = m.accumulatedDeltaRangeState,
                adrMeters = m.accumulatedDeltaRangeMeters,
                multipath = m.multipathIndicator,
                codeType = if (m.hasCodeType()) m.codeType else null,
                agcDb = if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb else null,
            )
        }
        val agc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            e.gnssAutomaticGainControls.map { RawAgc(constellationOf(it.constellationType), it.carrierFrequencyHz.toDouble(), it.levelDb) }
        } else {
            ms.filter { it.agcDb != null }.distinctBy { it.constellation to it.carrierHz }.map { RawAgc(it.constellation, it.carrierHz ?: 1575.42e6, it.agcDb!!) }
        }
        return RawEpoch(clock, ms, agc, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
    }

    /** Fix type from GSA and HDOP from GGA/GSA. */
    private fun parseNmea(msg: String): Pair<FixType?, Float?>? {
        val body = msg.trim().substringBefore('*')
        val f = body.split(',')
        if (f.isEmpty() || f[0].length < 6) return null
        return when (f[0].substring(3)) {
            "GSA" -> {
                val fix = when (f.getOrNull(2)) { "3" -> FixType.Fix3D; "2" -> FixType.Fix2D; "1" -> FixType.None; else -> null }
                fix to f.getOrNull(16)?.toFloatOrNull()
            }
            "GGA" -> {
                val quality = f.getOrNull(6)?.toIntOrNull()
                (if (quality == 0) FixType.None else null) to f.getOrNull(8)?.toFloatOrNull()
            }
            else -> null
        }
    }
}
