package com.allnetworktools.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** How good the signal is at a point, from its level and technology. */
enum class DriveQuality { Excellent, Good, Fair, Poor, NoService }

/** One measurement of a drive test: where, when, which cell and how strong. */
data class DrivePoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val speedKmh: Float?,
    val operator: String?,
    /** "5G", "4G+", "3G"… as the status bar shows it. */
    val techLabel: String,
    val tech: RadioTech?,
    val cell: String?,
    val band: String?,
    val pci: Int?,
    val arfcn: Int?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    /** The main level: RSRP in 4G/5G, RSCP or RSSI before. */
    val levelDbm: Int?,
    val quality: DriveQuality,
    /** The serving cell changed since the previous point. */
    val handover: Boolean,
)

/** Totals of a drive test. */
data class DriveSummary(
    val points: Int,
    val distanceKm: Double,
    val durationS: Long,
    val handovers: Int,
    /** Share of the distance in each quality, 0 to 1. */
    val quality: Map<DriveQuality, Double>,
    /** Share of the distance in each technology label. */
    val techs: Map<String, Double>,
    val worst: DrivePoint?,
)

object DriveQualities {
    /** Thresholds as operators and drive-test tools commonly draw them. */
    fun of(tech: RadioTech?, level: Int?, hasService: Boolean): DriveQuality {
        if (!hasService || tech == null || level == null) return DriveQuality.NoService
        val (a, b, c) = when (tech) {
            RadioTech.NR, RadioTech.LTE -> Triple(-90, -100, -110)
            RadioTech.WCDMA -> Triple(-85, -95, -105)
            RadioTech.GSM -> Triple(-75, -85, -95)
        }
        return when {
            level >= a -> DriveQuality.Excellent
            level >= b -> DriveQuality.Good
            level >= c -> DriveQuality.Fair
            else -> DriveQuality.Poor
        }
    }

    fun summary(points: List<DrivePoint>): DriveSummary {
        var km = 0.0
        val q = HashMap<DriveQuality, Double>()
        val t = HashMap<String, Double>()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = distanceM(a.lat, a.lon, b.lat, b.lon) / 1000
            // A jump (a lost fix, a long stop) is not driven distance.
            if (d > 2.0) continue
            km += d
            q[a.quality] = (q[a.quality] ?: 0.0) + d
            t[a.techLabel] = (t[a.techLabel] ?: 0.0) + d
        }
        fun share(m: Map<String, Double>) = if (km > 0) m.mapValues { it.value / km } else emptyMap()
        val qs = if (km > 0) q.mapValues { it.value / km } else points.groupingBy { it.quality }.eachCount().mapValues { it.value.toDouble() / points.size }
        val ts = if (km > 0) share(t) else points.groupingBy { it.techLabel }.eachCount().mapValues { it.value.toDouble() / points.size }
        return DriveSummary(
            points.size, km,
            if (points.size >= 2) (points.last().timeMs - points.first().timeMs) / 1000 else 0,
            points.count { it.handover }, qs, ts,
            points.filter { it.levelDbm != null }.minByOrNull { it.levelDbm!! } ?: points.firstOrNull { it.quality == DriveQuality.NoService },
        )
    }

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    fun csv(points: List<DrivePoint>): String = buildString {
        fun cell(v: Any?): String {
            val s = when (v) { null -> ""; is Double -> String.format(Locale.ROOT, "%.6f", v); is Float -> String.format(Locale.ROOT, "%.1f", v); else -> v.toString() }
            return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
        append("time_utc,latitude,longitude,accuracy_m,speed_kmh,operator,network,cell,band,pci,arfcn,rsrp_dbm,rsrq_db,sinr_db,rssi_dbm,level_dbm,quality,handover\r\n")
        points.forEach { p ->
            append(
                listOf(
                    utc(p.timeMs), p.lat, p.lon, p.accuracyM, p.speedKmh, p.operator, p.techLabel, p.cell, p.band, p.pci, p.arfcn,
                    p.rsrp, p.rsrq, p.sinr, p.rssi, p.levelDbm, p.quality.name.lowercase(), if (p.handover) 1 else 0,
                ).joinToString(",") { cell(it) },
            )
            append("\r\n")
        }
    }

    private fun xml(s: String?) = s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** KML for Google Earth or any map: the route drawn in the colour of the signal, one placemark per handover. */
    fun kml(points: List<DrivePoint>, title: String): String = buildString {
        // KML colours are aabbggrr.
        val colors = mapOf(
            DriveQuality.Excellent to "ff3cb44b", DriveQuality.Good to "ff8cd9a0", DriveQuality.Fair to "ff31c0f5",
            DriveQuality.Poor to "ff3c3ce6", DriveQuality.NoService to "ff202020",
        )
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        append("<name>").append(xml(title)).append("</name>\n")
        colors.forEach { (q, c) -> append("<Style id=\"${q.name}\"><LineStyle><color>$c</color><width>5</width></LineStyle><IconStyle><color>$c</color><scale>0.6</scale></IconStyle></Style>\n") }
        // Consecutive points of the same quality make one line, so the file stays small.
        var i = 0
        while (i < points.size - 1) {
            val q = points[i].quality
            var j = i + 1
            while (j < points.size - 1 && points[j].quality == q) j++
            append("<Placemark><styleUrl>#${q.name}</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
            for (k in i..j) append(String.format(Locale.ROOT, "%.6f,%.6f,0 ", points[k].lon, points[k].lat))
            append("</coordinates></LineString></Placemark>\n")
            i = j
        }
        points.filter { it.handover }.forEach { p ->
            append("<Placemark><name>").append(xml(p.cell ?: p.techLabel)).append("</name><description>")
            append(xml(listOfNotNull(p.operator, p.techLabel, p.band, p.levelDbm?.let { "$it dBm" }, utc(p.timeMs)).joinToString(" · ")))
            append("</description><styleUrl>#${p.quality.name}</styleUrl><Point><coordinates>")
            append(String.format(Locale.ROOT, "%.6f,%.6f,0", p.lon, p.lat)).append("</coordinates></Point></Placemark>\n")
        }
        append("</Document></kml>\n")
    }
}

/**
 * The drive test: the serving cell and its signal, recorded with the position along a trip, by a foreground service
 * so it keeps going with the screen off. A point is taken every [MIN_GAP_M] metres or [MAX_GAP_MS] milliseconds,
 * and at every change of cell. Each start begins an empty session; nothing is written to disk until exported.
 */
object DriveTest {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _points = MutableStateFlow<List<DrivePoint>>(emptyList())
    val points: StateFlow<List<DrivePoint>> = _points.asStateFlow()

    const val MIN_GAP_M = 15.0
    const val MAX_GAP_MS = 10_000L
    /** About a day of driving at one point every 15 m. */
    private const val MAX_POINTS = 100_000

    fun start(context: Context) {
        _points.value = emptyList()
        ContextCompat.startForegroundService(context, Intent(context, DriveTestService::class.java))
    }

    fun stop(context: Context) { context.stopService(Intent(context, DriveTestService::class.java)) }

    fun clear() { if (!_running.value) _points.value = emptyList() }

    internal fun setRunning(v: Boolean) { _running.value = v }

    internal fun setForTest(list: List<DrivePoint>) { _points.value = list }

    /** Adds a point when the trip moved on, enough time passed or the cell changed; returns it, or null. */
    fun observe(state: CellState, loc: Location?, now: Long = System.currentTimeMillis()): DrivePoint? {
        loc ?: return null
        // A fix from long ago (the phone indoors) does not place a measurement.
        if (loc.hasAccuracy() && loc.accuracy > 100f) return null
        val s = state.serving
        val cell = s?.let { HandoverLog.cellLabel(it) }
        val last = _points.value.lastOrNull()
        val moved = last?.let { DriveQualities.distanceM(it.lat, it.lon, loc.latitude, loc.longitude) } ?: Double.MAX_VALUE
        val changed = last != null && (last.cell != cell || last.techLabel != state.techLabel)
        if (last != null && !changed && moved < MIN_GAP_M && now - last.timeMs < MAX_GAP_MS) return null
        val p = DrivePoint(
            now, loc.latitude, loc.longitude, loc.takeIf { it.hasAccuracy() }?.accuracy, loc.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
            state.operator, if (state.hasService) state.techLabel else "—", s?.tech, cell, s?.band, s?.pci, s?.arfcn,
            s?.rsrp, s?.rsrq, s?.sinr, s?.rssi, s?.level, DriveQualities.of(s?.tech, s?.level, state.hasService && s != null), changed,
        )
        _points.value = (_points.value + p).takeLast(MAX_POINTS)
        return p
    }
}

/** Foreground service of the drive test; the notification shows the distance and a stop button. */
class DriveTestService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocation) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification(0.0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (job == null) {
            val app = application as AntApplication
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = sc
            DriveTest.setRunning(true)
            job = sc.launch {
                combine(app.cell.cells(2_000), app.gnss.positions(2_000)) { c, p -> c to p }.collect { (c, p) ->
                    if (DriveTest.observe(c, p.gnss ?: p.fused) != null) {
                        val now = System.currentTimeMillis()
                        if (now - lastNotified > 10_000) {
                            lastNotified = now
                            val pts = DriveTest.points.value
                            val sum = DriveQualities.summary(pts)
                            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(sum.distanceKm, pts.size))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        DriveTest.setRunning(false)
        super.onDestroy()
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun notification(km: Double, points: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.drive_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 21, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DRIVE_TEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(this, 22, Intent(this, DriveTestService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.drive_running))
            .setContentText(S(R.string.drive_notification, String.format(Locale.getDefault(), "%.1f", km), points))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "drivetest"
        private const val NOTIFICATION_ID = 61
        const val ACTION_STOP = "com.allnetworktools.DRIVE_TEST_STOP"
    }
}
