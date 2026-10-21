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
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
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
import com.allnetworktools.util.S

/** A change of serving cell (or of technology) during a session, with where it happened. */
data class HandoverEvent(
    val timeMs: Long,
    val fromTech: String?,
    val fromCell: String?,
    val toTech: String,
    val toCell: String?,
    val band: String?,
    val pci: Int?,
    val arfcn: Int?,
    val levelDbm: Int?,
    val lat: Double?,
    val lon: Double?,
    val operator: String?,
) {
    val isStart get() = fromTech == null && fromCell == null
}

/**
 * The handover journal: the serving cell is watched by a foreground service, so it keeps going with the app closed,
 * until it is stopped from the app or the notification. Each start begins an empty session; nothing is written to disk.
 */
object HandoverLog {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _events = MutableStateFlow<List<HandoverEvent>>(emptyList())
    val events: StateFlow<List<HandoverEvent>> = _events.asStateFlow()
    var startedAt = 0L
        private set

    /** Keeps memory bounded on very long sessions. */
    private const val MaxEvents = 20_000

    fun start(context: Context) {
        _events.value = emptyList()
        startedAt = System.currentTimeMillis()
        ContextCompat.startForegroundService(context, Intent(context, HandoverService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, HandoverService::class.java))
    }

    internal fun setRunning(v: Boolean) { _running.value = v }

    /** Compares [state] with the last event and records a new one when the serving cell or the technology changed. */
    fun observe(state: CellState, pos: PositionSet, now: Long = System.currentTimeMillis()): HandoverEvent? {
        val s = state.serving ?: return null
        val cell = cellLabel(s)
        val last = _events.value.lastOrNull()
        if (last != null && last.toCell == cell && last.toTech == state.techLabel) return null
        val loc = pos.fused ?: pos.gnss ?: pos.network
        val e = HandoverEvent(
            now, last?.toTech, last?.toCell, state.techLabel, cell, s.band, s.pci, s.arfcn, s.level,
            loc?.latitude, loc?.longitude, state.operator,
        )
        _events.value = (_events.value + e).takeLast(MaxEvents)
        return e
    }

    /** What identifies the cell: its global id when the modem gives it, else channel and PCI. */
    fun cellLabel(s: CellMeasure): String = s.cellId?.let { "${s.tech.short} $it" } ?: "${s.tech.short} ${s.arfcn ?: "?"}/${s.pci ?: "?"}"

    internal fun setForTest(list: List<HandoverEvent>) { _events.value = list }

    private fun utc(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun cell(v: Any?): String {
        val s = when (v) { null -> ""; is Double -> String.format(Locale.ROOT, "%.6f", v); else -> v.toString() }
        return if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun csv(list: List<HandoverEvent> = _events.value): String = buildString {
        append("heure_utc,operateur,de_techno,de_cellule,vers_techno,vers_cellule,bande,pci,arfcn,niveau_dbm,latitude,longitude\r\n")
        list.forEach { e ->
            append(listOf(utc(e.timeMs), e.operator, e.fromTech, e.fromCell, e.toTech, e.toCell, e.band, e.pci, e.arfcn, e.levelDbm, e.lat, e.lon).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }
}

/** Foreground service running the handover journal; the notification shows the count and a stop button. */
class HandoverService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(HandoverLog.events.value.count { !it.isStart }), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (job == null) {
            val app = application as AntApplication
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = sc
            HandoverLog.setRunning(true)
            job = sc.launch {
                combine(app.cell.cells(2_000), app.gnss.positions(5_000)) { c, p -> c to p }.collect { (c, p) ->
                    if (HandoverLog.observe(c, p) != null) {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(HandoverLog.events.value.count { !it.isStart }))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        HandoverLog.setRunning(false)
        super.onDestroy()
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun notification(handovers: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.handover_log), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HandoverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.handover_log_running))
            .setContentText(S(R.string.cell, handovers, if (handovers > 1) "changements" else "changement"))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "handover"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.allnetworktools.HANDOVER_STOP"
    }
}
