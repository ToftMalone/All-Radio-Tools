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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CellWatchState(
    val running: Boolean = false,
    val serving: CellMeasure? = null,
    val neighbours: Int = 0,
    val anomalies: List<CellAnomaly> = emptyList(),
    val sinceMs: Long = 0,
    val cellsSeen: Int = 0,
)

/** Bridge between the background fake-base-station watch and its screen. */
object CellWatchHub {
    private val _state = MutableStateFlow(CellWatchState())
    val state: StateFlow<CellWatchState> = _state.asStateFlow()

    internal val watch = CellWatch()
    private val seen = HashSet<String>()

    @Volatile
    var watching = false

    internal fun update(s: CellState?, found: List<CellAnomaly>) {
        s?.serving?.let { seen += CellWatch.label(it) }
        val cur = _state.value
        _state.value = cur.copy(
            running = true,
            serving = s?.serving ?: cur.serving,
            neighbours = s?.neighbors?.size ?: cur.neighbours,
            anomalies = (found.reversed() + cur.anomalies).take(200),
            cellsSeen = seen.size,
        )
    }

    internal fun add(a: CellAnomaly) { _state.value = _state.value.copy(anomalies = (listOf(a) + _state.value.anomalies).take(200)) }
    internal fun setRunning(on: Boolean) {
        _state.value = _state.value.copy(running = on, sinceMs = if (on) System.currentTimeMillis() else _state.value.sinceMs)
    }

    fun clear() {
        watch.reset(); seen.clear()
        _state.value = _state.value.copy(anomalies = emptyList(), cellsSeen = 0, sinceMs = System.currentTimeMillis())
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, CellWatchService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, CellWatchService::class.java).setAction(CellWatchService.ACTION_STOP))
    }
}

/** Foreground service watching the serving cell for the signs of a fake base station, with the app closed too. */
@SuppressLint("MissingPermission")
class CellWatchService : Service() {
    private var scope: CoroutineScope? = null
    private val anfrCache = HashMap<String, List<TowerSite>>()

    @Volatile
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Cell identities need precise location and the phone state permission.
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) || !has(Manifest.permission.READ_PHONE_STATE)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, ongoing(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (scope == null) {
            val app = application as AntApplication
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = sc
            CellWatchHub.setRunning(true)
            sc.launch {
                combine(app.cell.cells(3_000), app.gnss.positions(15_000)) { c, p -> c to p }.collect { (cell, p) ->
                    val loc = p.fused ?: p.gnss ?: p.network
                    val pos = loc?.let { it.latitude to it.longitude }
                    val found = CellWatchHub.watch.observe(cell, pos, System.currentTimeMillis())
                    CellWatchHub.update(cell, found)
                    found.forEach(::alert)
                    CellWatchHub.watch.newCell?.let { n -> sc.launch { checkAnfr(app, n) } }
                    notifyOngoing()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Sites of the operator within 3 km, cached per ~1 km square; offline or on error, nothing is concluded. */
    private suspend fun checkAnfr(app: AntApplication, n: CellWatch.NewCell) {
        val key = "${n.operator}-${(n.lat * 100).roundToInt()}-${(n.lon * 100).roundToInt()}"
        val sites = anfrCache[key] ?: runCatching { app.towers.fetch(TowerQuery(n.operator, n.lat, n.lon, 3_000)).sites }.getOrNull()?.also { anfrCache[key] = it } ?: return
        if (sites.isEmpty()) return
        if (sites.none { s -> s.emitters.any { n.generation in it.generation } }) {
            CellWatchHub.watch.anfrMissing(n, System.currentTimeMillis())?.let { CellWatchHub.add(it); alert(it) }
        }
    }

    override fun onDestroy() {
        closing = true
        scope?.cancel(); scope = null
        CellWatchHub.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private var lastCount = -1

    private fun notifyOngoing() {
        val n = CellWatchHub.state.value.anomalies.size
        if (closing || n == lastCount) return
        lastCount = n
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing(n))
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 8, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_FAKE_CELLS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(anomalies: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.fake_cell_detector), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9, Intent(this, CellWatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.fake_cell_watch_running))
            .setContentText(S(R.string.fake_cell_anomalies_n, anomalies))
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    /** Only the strongest signs raise an alert; the weak ones stay in the list. */
    private fun alert(a: CellAnomaly) {
        if (closing || a.kind.severity < 2 || CellWatchHub.watching) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, S(R.string.fake_cell_alerts), NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.suspicious_cell))
            .setContentText(anomalyTitle(a.kind) + " · " + a.cell)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        nm.notify(ALERT_BASE + a.kind.ordinal, n)
    }

    companion object {
        private const val CHANNEL = "cell_watch"
        private const val ALERT_CHANNEL = "cell_watch_alerts"
        private const val NOTIFICATION_ID = 46
        private const val ALERT_BASE = 30_000
        const val ACTION_STOP = "com.allnetworktools.CELL_WATCH_STOP"
    }
}

fun anomalyTitle(k: CellAnomalyKind): String = when (k) {
    CellAnomalyKind.TestNetwork -> S(R.string.anomaly_test_network)
    CellAnomalyKind.TacMismatch -> S(R.string.anomaly_tac_mismatch)
    CellAnomalyKind.Downgrade2G -> S(R.string.anomaly_downgrade_2g)
    CellAnomalyKind.TacChangedStill -> S(R.string.anomaly_tac_changed_still)
    CellAnomalyKind.NotInAnfr -> S(R.string.anomaly_not_in_anfr)
    CellAnomalyKind.NoNeighbours -> S(R.string.anomaly_no_neighbours)
    CellAnomalyKind.StrongNewCell -> S(R.string.anomaly_strong_new_cell)
    CellAnomalyKind.UnknownOperator -> S(R.string.anomaly_unknown_operator)
}

fun anomalyBody(k: CellAnomalyKind): String = when (k) {
    CellAnomalyKind.TestNetwork -> S(R.string.anomaly_test_network_body)
    CellAnomalyKind.TacMismatch -> S(R.string.anomaly_tac_mismatch_body)
    CellAnomalyKind.Downgrade2G -> S(R.string.anomaly_downgrade_2g_body)
    CellAnomalyKind.TacChangedStill -> S(R.string.anomaly_tac_changed_still_body)
    CellAnomalyKind.NotInAnfr -> S(R.string.anomaly_not_in_anfr_body)
    CellAnomalyKind.NoNeighbours -> S(R.string.anomaly_no_neighbours_body)
    CellAnomalyKind.StrongNewCell -> S(R.string.anomaly_strong_new_cell_body)
    CellAnomalyKind.UnknownOperator -> S(R.string.anomaly_unknown_operator_body)
}
