package com.allnetworktools.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.BuildConfig
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a line of the list is drawn: the lock of a Wi-Fi network, or Bluetooth. */
enum class Guard { Open, Wep, Wpa, Wpa3, Ble }

/** One network of the live list. */
data class WardriveRow(
    val mac: String,
    val name: String,
    val type: NetType,
    val level: Int,
    val frequency: Int,
    /** Wi-Fi: the capabilities as WiGLE shows them ("[WPA2][RSN][ESS]"); Bluetooth: its kind. */
    val detail: String,
    val vendor: String?,
    val guard: Guard,
    /** Wall-clock time it was last heard. */
    val seenAt: Long,
)

/** A position good enough to record with. */
data class WardriveFix(val lat: Double, val lon: Double, val altitude: Double, val accuracy: Float, val gps: Boolean)

sealed interface UploadState {
    data object Idle : UploadState
    data object Sending : UploadState
    data class Done(val count: Long) : UploadState
    data object Nothing : UploadState
    data class Failed(val message: String) : UploadState
}

data class WardriveState(
    val running: Boolean = false,
    val rows: List<WardriveRow> = emptyList(),
    /** Networks first met during this run, per type. */
    val newWifi: Int = 0,
    val newBle: Int = 0,
    val db: DbCounts = DbCounts(),
    val fix: WardriveFix? = null,
    val lastScanCount: Int = 0,
    val lastScanMs: Long? = null,
    /** Sightings waiting to be written to the database. */
    val queue: Int = 0,
    /** Sightings not sent to WiGLE yet. */
    val pending: Long = 0,
    val upload: UploadState = UploadState.Idle,
) {
    val newTotal get() = newWifi + newBle
}

/**
 * Wardriving: Wi-Fi and Bluetooth networks met along the way, each with the place it was heard, kept in a database on
 * the phone and sent to WiGLE.net only when the user asks. The session lives for the process, run by a foreground
 * service so it goes on with the screen off; the screen shows what it records.
 */
object Wardrive {
    private val _state = MutableStateFlow(WardriveState())
    val state: StateFlow<WardriveState> = _state.asStateFlow()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Hold only the application context.
    @SuppressLint("StaticFieldLeak")
    private var dbInstance: WardriveDb? = null
    @SuppressLint("StaticFieldLeak")
    private var settingsInstance: WardriveSettingsRepository? = null
    @SuppressLint("StaticFieldLeak")
    private var ouiInstance: OuiTable? = null

    @Synchronized fun db(c: Context) = dbInstance ?: WardriveDb(c.applicationContext).also { dbInstance = it }
    @Synchronized fun settings(c: Context) = settingsInstance ?: WardriveSettingsRepository(c.applicationContext).also { settingsInstance = it }
    @Synchronized fun oui(c: Context) = ouiInstance ?: OuiTable.of(c.applicationContext).also { ouiInstance = it }

    internal fun update(f: (WardriveState) -> WardriveState) = _state.update(f)

    /** Database totals and what is left to send, read again (at start, after a write, an upload or a purge). */
    suspend fun refreshCounts(c: Context) {
        val upTo = settings(c).settings.first().uploadedUpTo
        val (counts, pending) = withContext(Dispatchers.IO) { db(c).counts() to db(c).sightingsAfter(upTo) }
        update { it.copy(db = counts, pending = pending) }
    }

    fun load(c: Context) { appScope.launch { refreshCounts(c) } }

    fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, WardriveService::class.java))

    fun stop(context: Context) {
        context.startService(Intent(context, WardriveService::class.java).setAction(WardriveService.ACTION_STOP))
    }

    /** Sends every sighting not sent yet to WiGLE.net, by hand only. */
    fun upload(c: Context) {
        if (_state.value.upload == UploadState.Sending) return
        update { it.copy(upload = UploadState.Sending) }
        appScope.launch {
            val s = settings(c).settings.first()
            val result = withContext(Dispatchers.IO) {
                val db = db(c)
                val count = db.sightingsAfter(s.uploadedUpTo)
                val file = WigleClient.build(db, s.uploadedUpTo, BuildConfig.VERSION_NAME) ?: return@withContext UploadState.Nothing
                val name = "AllRadioTools_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".csv.gz"
                when (val r = WigleClient.upload(s.apiName, s.apiToken, name, file.first)) {
                    is WigleClient.Result.Ok -> { settings(c).setUploadedUpTo(file.second); UploadState.Done(count) }
                    is WigleClient.Result.Failed -> UploadState.Failed(r.message)
                }
            }
            update { it.copy(upload = result) }
            refreshCounts(c)
        }
    }

    fun dismissUpload() = update { it.copy(upload = UploadState.Idle) }

    /** New exclusions: the matching networks also leave the database and the list. */
    fun applyExclusions(c: Context, entries: Set<String>) {
        appScope.launch {
            settings(c).setExclusions(entries)
            val ex = Exclusions(entries)
            withContext(Dispatchers.IO) { db(c).purge(ex) }
            update { st -> st.copy(rows = st.rows.filterNot { ex.excludes(it.mac) }) }
            refreshCounts(c)
        }
    }

    fun clearDatabase(c: Context) {
        appScope.launch {
            withContext(Dispatchers.IO) { db(c).clear() }
            settings(c).setUploadedUpTo(0)
            update { it.copy(newWifi = 0, newBle = 0) }
            refreshCounts(c)
        }
    }

    /** "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" as WiGLE shows it: "[WPA2][RSN][ESS]". */
    fun shortCaps(caps: String): String = Regex("\\[([^\\]]*)]").findAll(caps).joinToString("") { m ->
        "[" + m.groupValues[1].substringBefore('-') + "]"
    }

    fun guardOf(caps: String): Guard = when {
        "SAE" in caps || "WPA3" in caps || "OWE" in caps -> Guard.Wpa3
        "WPA" in caps || "RSN" in caps -> Guard.Wpa
        "WEP" in caps -> Guard.Wep
        else -> Guard.Open
    }
}

/**
 * The recording loop: Wi-Fi scans back to back, Bluetooth LE heard all along, positions every second. A network is
 * written when first met, then again once it is heard 10 m further on or 3 dB stronger, so standing still does not
 * fill the database.
 */
@SuppressLint("MissingPermission")
class WardriveEngine(private val app: AntApplication, private val scope: CoroutineScope) {
    private val db = Wardrive.db(app)
    private val oui = Wardrive.oui(app)
    private val queue = Channel<List<NetSighting>>(Channel.UNLIMITED)
    private val queued = AtomicInteger()
    private var fix: WardriveFix? = null
    private var fixAt = 0L
    private var exclusions = Exclusions(emptyList())
    private var wifiRows: List<WardriveRow> = emptyList()
    private var bleRows: List<WardriveRow> = emptyList()
    private val written = HashMap<String, Triple<Double, Double, Int>>()
    private var scanStarted = 0L
    private val resultsIn = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch { Wardrive.settings(app).settings.collect { exclusions = Exclusions(it.exclusions) } }
        scope.launch { Wardrive.refreshCounts(app) }
        scope.launch(Dispatchers.IO) {
            for (batch in queue) {
                val fresh = runCatching { db.write(batch) }.getOrDefault(emptyMap())
                queued.addAndGet(-batch.size)
                Wardrive.update { it.copy(newWifi = it.newWifi + (fresh[NetType.Wifi] ?: 0), newBle = it.newBle + (fresh[NetType.Ble] ?: 0), queue = queued.get()) }
                Wardrive.refreshCounts(app)
            }
        }
        scope.launch {
            app.gnss.positions(1_000).collect { set ->
                val now = SystemClock.elapsedRealtimeNanos()
                fun fresh(l: Location?, maxS: Int) = l != null && now - l.elapsedRealtimeNanos < maxS * 1_000_000_000L
                val g = set.gnss
                val f = set.fused
                // GPS first; the fused position only when it is as good as a GPS fix.
                val pick = when {
                    fresh(g, 3) -> g!! to true
                    fresh(f, 5) && f!!.accuracy <= 20f -> f to false
                    else -> null
                }
                fix = pick?.let { (l, gps) -> WardriveFix(l.latitude, l.longitude, l.altitude, l.accuracy, gps) }
                if (pick != null) fixAt = SystemClock.elapsedRealtime()
                Wardrive.update { it.copy(fix = fix) }
            }
        }
        scope.launch {
            app.wifi.scanResults.collect { list ->
                val now = System.currentTimeMillis()
                val hidden = S(R.string.hidden_network)
                val rows = list.filter { it.bssid.isNotEmpty() && !exclusions.excludes(it.bssid) }.map { ap ->
                    val name = if (ap.ssid == hidden) "" else ap.ssid
                    WardriveRow(ap.bssid, name, NetType.Wifi, ap.rssi, ap.frequency, Wardrive.shortCaps(ap.caps), oui.vendor(ap.bssid), Wardrive.guardOf(ap.caps), now)
                }
                wifiRows = rows
                val took = if (scanStarted > 0) SystemClock.elapsedRealtime() - scanStarted else null
                scanStarted = 0
                Wardrive.update { it.copy(lastScanCount = rows.size, lastScanMs = took ?: it.lastScanMs) }
                publish()
                record(list.filter { it.bssid.isNotEmpty() && !exclusions.excludes(it.bssid) }.map { ap ->
                    val name = if (ap.ssid == hidden) "" else ap.ssid
                    Triple(ap.bssid, ap.rssi, { f: WardriveFix -> NetSighting(ap.bssid, name, ap.frequency, ap.caps, NetType.Wifi, ap.rssi, f.lat, f.lon, f.altitude, f.accuracy, now) })
                })
                resultsIn.trySend(Unit)
            }
        }
        scope.launch {
            // Back to back, as fast as Android allows (4 scans in 2 minutes unless the developer option lifts it).
            while (isActive) {
                scanStarted = SystemClock.elapsedRealtime()
                if (app.wifi.startScan()) {
                    withTimeoutOrNull(15_000) { resultsIn.receive() }
                    delay(1_000)
                } else {
                    scanStarted = 0
                    val wait = app.wifi.throttledUntil.value - SystemClock.elapsedRealtime()
                    delay(wait.coerceIn(2_000, 30_000))
                }
            }
        }
        scope.launch {
            // An empty filter keeps the scan going with the screen off, which an unfiltered scan does not.
            var lastTick = 0L
            app.bluetooth.bleScan(lowPower = false, staleMs = 15_000, filters = listOf(ScanFilter.Builder().build())).collect { devices ->
                val nowEl = SystemClock.elapsedRealtime()
                val wall = System.currentTimeMillis()
                val kept = devices.filter { !exclusions.excludes(it.address) }
                bleRows = kept.map { d ->
                    WardriveRow(d.address, d.name.orEmpty(), NetType.Ble, d.rssi, 0, "BLE | " + d.kind.label, d.maker ?: oui.vendor(d.address), Guard.Ble, wall - (nowEl - d.lastSeen))
                }
                // The list follows every packet; the database once a second.
                if (nowEl - lastTick < 1_000) return@collect
                val since = lastTick
                lastTick = nowEl
                publish()
                record(kept.filter { it.lastSeen > since }.map { d ->
                    val at = wall - (nowEl - d.lastSeen)
                    Triple(d.address, d.rssi, { f: WardriveFix -> NetSighting(d.address, d.name.orEmpty(), 0, "", NetType.Ble, d.rssi, f.lat, f.lon, f.altitude, f.accuracy, at) })
                })
            }
        }
    }

    private fun publish() {
        val rows = (wifiRows + bleRows).sortedByDescending { it.level }
        Wardrive.update { it.copy(rows = rows) }
    }

    private fun record(heard: List<Triple<String, Int, (WardriveFix) -> NetSighting>>) {
        val f = fix ?: return
        if (SystemClock.elapsedRealtime() - fixAt > 5_000) return
        val out = ArrayList<NetSighting>()
        for ((mac, level, make) in heard) {
            val w = written[mac]
            if (w != null && distanceM(w.first, w.second, f.lat, f.lon) < 10 && level < w.third + 3) continue
            written[mac] = Triple(f.lat, f.lon, if (w == null || level > w.third) level else w.third)
            out += make(f)
        }
        if (out.isEmpty()) return
        queued.addAndGet(out.size)
        Wardrive.update { it.copy(queue = queued.get()) }
        queue.trySend(out)
    }

    fun close() = queue.close()

    companion object {
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = FloatArray(1)
            Location.distanceBetween(lat1, lon1, lat2, lon2, r)
            return r[0].toDouble().takeIf { !it.isNaN() } ?: (abs(lat1 - lat2) + abs(lon1 - lon2)) * 111_000
        }
    }
}

/** Foreground service running the recording: it carries on with the screen off and the app closed. */
class WardriveService : Service() {
    private var scope: CoroutineScope? = null
    private var engine: WardriveEngine? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, ongoing(Wardrive.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (engine == null) {
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = sc
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AllRadioTools:wardriving")?.apply { acquire() }
            Wardrive.update { it.copy(running = true, newWifi = 0, newBle = 0) }
            engine = WardriveEngine(application as AntApplication, sc).also { it.start() }
            sc.launch {
                Wardrive.state.collect { st ->
                    val text = notificationText(st)
                    if (text != lastText) {
                        lastText = text
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing(st))
                    }
                }
            }
        }
        // Killed for memory: Android starts it again and the recording resumes.
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.close(); engine = null
        scope?.cancel(); scope = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        Wardrive.update { it.copy(running = false, rows = emptyList(), fix = null, queue = 0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun notificationText(st: WardriveState) =
        S(R.string.wardriving_notification, st.newWifi, st.newBle, st.db.total.toInt()) + if (st.fix == null) " · " + S(R.string.wardriving_waiting_gps) else ""

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(st: WardriveState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Wardriving", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9, Intent(this, WardriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 8, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_WARDRIVING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.wardriving_running))
            .setContentText(notificationText(st))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "wardriving"
        private const val NOTIFICATION_ID = 47
        const val ACTION_STOP = "com.allnetworktools.WARDRIVING_STOP"
    }
}
