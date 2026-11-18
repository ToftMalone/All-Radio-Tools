package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The distance to one access point, from its last round-trip measurements. One measurement scatters by a metre or
 * more (multipath, a body in the way): the median of the last few is shown, and the spread between them.
 */
class RttTrack(val bssid: String, val ssid: String, val frequency: Int) {
    private val recent = ArrayDeque<Double>()
    var rssi: Int? = null; private set
    var lastMs = 0L; private set
    var attempts = 0; private set
    var successes = 0; private set
    /** What this access point reads when the phone is right next to it, taken away from every distance. */
    var offsetM = 0.0
    /** The device's own spread for the last measurement, metres. */
    var deviceStdM: Double? = null; private set
    private var previous: Double? = null
    private var previousMs = 0L

    /** Metres, offset taken away, never below zero; null before the first success. */
    val distanceM: Double? get() = median()?.let { (it - offsetM).coerceAtLeast(0.0) }

    /** How far apart the recent measurements are (half the range between the quartiles), metres. */
    val spreadM: Double? get() {
        if (recent.size < 3) return deviceStdM
        val s = recent.sorted()
        return (s[(s.size * 3) / 4] - s[s.size / 4]) / 2
    }

    /** Metres per second, positive when moving away; null until two readings a few seconds apart. */
    var speedMs: Double? = null; private set

    fun add(distanceMm: Int, stdMm: Int, rssi: Int, nowMs: Long, success: Boolean) {
        attempts++
        if (!success) return
        successes++
        this.rssi = rssi
        lastMs = nowMs
        deviceStdM = stdMm / 1000.0
        recent.addLast(distanceMm / 1000.0)
        while (recent.size > WINDOW) recent.removeFirst()
        val m = median() ?: return
        val p = previous
        if (p == null) { previous = m; previousMs = nowMs }
        else if (nowMs - previousMs >= 3_000) { speedMs = (m - p) * 1000.0 / (nowMs - previousMs); previous = m; previousMs = nowMs }
    }

    /** Takes the current reading as 0 m (the phone held against the access point). */
    fun calibrateHere() { median()?.let { offsetM = it } }

    private fun median(): Double? {
        if (recent.isEmpty()) return null
        val s = recent.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object { const val WINDOW = 7 }
}

/** What the tool shows: whether the phone can range, the access points that answer, and their tracks. */
data class RttState(
    val supported: Boolean,
    val available: Boolean,
    val responders: Int,
    val heard: Int,
    val tracks: List<RttTrack>,
    val version: Int,
    val error: Int? = null,
)

/**
 * Wi-Fi RTT (IEEE 802.11mc, "fine timing measurement"): the phone times a radio round trip to access points that
 * answer it, and that time of flight gives the distance to within one or two metres, without connecting to them.
 * Only access points built for it answer (many recent mesh systems and Wi-Fi 6/7 boxes; the scan says which).
 */
class WifiRttRanger(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val rtt: WifiRttManager? = context.getSystemService(WifiRttManager::class.java)
    val supported: Boolean = rtt != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)

    private val tracks = LinkedHashMap<String, RttTrack>()
    private var responders: List<ScanResult> = emptyList()
    private var heard = 0
    private var version = 0
    private var lastError: Int? = null
    private val _state = MutableStateFlow(RttState(supported, false, 0, 0, emptyList(), 0))
    val state: StateFlow<RttState> = _state.asStateFlow()
    private var job: Job? = null
    private var receiver: BroadcastReceiver? = null

    private fun publish() {
        version++
        _state.value = RttState(supported, rtt?.isAvailable == true, responders.size, heard, tracks.values.sortedBy { it.distanceM ?: 1e9 }, version, lastError)
    }

    /** Ranges every second while [scope] lives, refreshing the list of access points from the scan now and then. */
    fun start(scope: CoroutineScope) {
        if (!supported || job != null) { publish(); return }
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = publish() }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        job = scope.launch {
            var lastScan = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastScan > 30_000) {
                    // Android limits scans; when refused, the last results still list the access points.
                    @Suppress("DEPRECATION") runCatching { wifi?.startScan() }
                    lastScan = now
                }
                readScan()
                range()
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    fun calibrate(bssid: String) { tracks[bssid]?.calibrateHere(); publish() }

    fun clearCalibration(bssid: String) { tracks[bssid]?.offsetM = 0.0; publish() }

    @SuppressLint("MissingPermission")
    private fun readScan() {
        val all = runCatching { wifi?.scanResults }.getOrNull().orEmpty()
        heard = all.size
        responders = all.filter { isResponder(it) }.sortedByDescending { it.level }
        for (s in responders) {
            if (s.BSSID !in tracks) tracks[s.BSSID] = RttTrack(s.BSSID, ssidOf(s), s.frequency)
        }
        publish()
    }

    private fun isResponder(s: ScanResult): Boolean =
        s.is80211mcResponder || (Build.VERSION.SDK_INT >= 33 && runCatching { s.is80211azNtbResponder }.getOrDefault(false))

    @Suppress("DEPRECATION")
    private fun ssidOf(s: ScanResult): String =
        (if (Build.VERSION.SDK_INT >= 33) s.wifiSsid?.toString()?.removeSurrounding("\"") else s.SSID)?.takeIf { it.isNotBlank() } ?: "—"

    @SuppressLint("MissingPermission")
    private fun range() {
        val m = rtt ?: return
        if (!m.isAvailable || responders.isEmpty()) return
        val peers = responders.take(RangingRequest.getMaxPeers())
        val req = RangingRequest.Builder().apply { peers.forEach { addAccessPoint(it) } }.build()
        runCatching {
            m.startRanging(req, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { lastError = code; publish() }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    lastError = null
                    val now = System.currentTimeMillis()
                    for (res in results) {
                        val b = res.macAddress?.toString() ?: continue
                        val t = tracks.entries.firstOrNull { it.key.equals(b, true) }?.value ?: continue
                        val ok = res.status == RangingResult.STATUS_SUCCESS
                        if (ok) t.add(res.distanceMm, res.distanceStdDevMm, res.rssi, now, true) else t.add(0, 0, 0, now, false)
                    }
                    publish()
                }
            })
        }.onFailure { lastError = -1; publish() }
    }
}
