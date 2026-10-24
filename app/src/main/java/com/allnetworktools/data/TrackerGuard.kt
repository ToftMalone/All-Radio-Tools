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
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.ui.pages.bt.UnknownTrackersController
import com.allnetworktools.util.S
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

/**
 * The unknown-tracker session lives for the whole process, so the background guard and the screen share it:
 * open the tool while the guard runs and you see what it recorded on the way.
 */
object TrackerGuard {
    // Holds only the application context, never an activity.
    @SuppressLint("StaticFieldLeak")
    private var instance: UnknownTrackersController? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Synchronized
    fun controller(app: Context): UnknownTrackersController =
        instance ?: UnknownTrackersController(app.applicationContext, appScope).also { instance = it }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun setRunning(v: Boolean) { _running.value = v }

    /** The tool is on screen: no alert for what the user is already looking at. */
    @Volatile
    var watching = false

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, TrackerGuardService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, TrackerGuardService::class.java).setAction(TrackerGuardService.ACTION_STOP))
    }

    /**
     * Only the advertisements tracker networks use, so Android keeps delivering results with the screen off:
     * Find My (Apple, offline finding), Find Hub (Google), SmartThings Find (Samsung) and Tile.
     */
    val filters: List<ScanFilter> = buildList {
        add(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x12), byteArrayOf(0xFF.toByte())).build())
        fun uuid(u: Int) = ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(u))
        add(ScanFilter.Builder().setServiceData(uuid(0xFEAA), byteArrayOf(0x40), byteArrayOf(0xFE.toByte())).build())
        add(ScanFilter.Builder().setServiceData(uuid(0xFD5A), byteArrayOf()).build())
        listOf(0xFD5A, 0xFEED, 0xFEEC, 0xFD84).forEach { add(ScanFilter.Builder().setServiceUuid(uuid(it)).build()) }
    }
}

/** Foreground service feeding the unknown-tracker session with the app closed, and alerting on a tag that follows. */
@SuppressLint("MissingPermission")
class TrackerGuardService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val alerted = HashSet<TrackerKey>()
    private var lastSummary = ""

    @Volatile
    private var closing = false

    /** A tag changes address: alert once per candidate object, not per address. */
    private data class TrackerKey(val id: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!has(Manifest.permission.BLUETOOTH_SCAN) || !has(Manifest.permission.BLUETOOTH_CONNECT)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // With location, the guard also measures how far a tag travelled with you.
        val located = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (located) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        startForeground(NOTIFICATION_ID, ongoing(0, 0), type)
        if (job == null) {
            val app = application as AntApplication
            val c = TrackerGuard.controller(app)
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = sc
            TrackerGuard.setRunning(true)
            val positions = if (located) app.gnss.positions(10_000) else kotlinx.coroutines.flow.flowOf(PositionSet())
            job = sc.launch {
                combine(app.bluetooth.bleScan(lowPower = true, staleMs = 30_000, filters = TrackerGuard.filters), positions) { d, p -> d to p }
                    .collect { (devices, pos) ->
                        c.feed(devices, pos)
                        update(c)
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        TrackerGuard.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update(c: UnknownTrackersController) {
        if (closing) return
        val list = c.candidates.values.toList()
        val following = list.filter { it.assessment.level == FollowLevel.Following }
        val summary = "${list.size}/${following.size}"
        if (summary != lastSummary) {
            lastSummary = summary
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing(list.size, following.size))
        }
        following.forEach { t ->
            val key = TrackerKey(System.identityHashCode(t))
            if (alerted.add(key) && !TrackerGuard.watching) alert(t.kind.label, t.lastHeardMs - t.firstHeardMs, System.identityHashCode(t))
        }
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 6, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TRACKERS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(total: Int, following: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.tracker_guard), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 7, Intent(this, TrackerGuardService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.tracker_guard_running))
            .setContentText(S(R.string.tracker_guard_count, total, following))
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    private fun alert(kind: String, withYouMs: Long, id: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, S(R.string.tracker_alerts), NotificationManager.IMPORTANCE_HIGH))
        val min = (withYouMs / 60_000).coerceAtLeast(1)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.tracker_following_title))
            .setContentText(S(R.string.tracker_following_text, kind, min.toInt()))
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        nm.notify(ALERT_BASE + (id and 0xFFFF), n)
    }

    companion object {
        private const val CHANNEL = "tracker_guard"
        private const val ALERT_CHANNEL = "tracker_alerts"
        private const val NOTIFICATION_ID = 45
        private const val ALERT_BASE = 20_000
        const val ACTION_STOP = "com.allnetworktools.TRACKER_GUARD_STOP"
    }
}
