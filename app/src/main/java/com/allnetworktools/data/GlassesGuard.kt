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
import androidx.core.content.ContextCompat
import com.allnetworktools.AntApplication
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The smart glasses session lives for the whole process: the background engine and the screen share it. */
object GlassesGuard {
    val watch = GlassesWatch()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun setRunning(v: Boolean) { _running.value = v }

    /** The tool is on screen: no alert for what the user is already looking at. */
    @Volatile
    var watching = false

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, GlassesGuardService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, GlassesGuardService::class.java).setAction(GlassesGuardService.ACTION_STOP))
    }
}

/** Foreground service watching for camera glasses with the app closed, alerting when a pair comes close. */
@SuppressLint("MissingPermission")
class GlassesGuardService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    /** When each address last raised an alert, and the last alert of all: no flood when someone stands nearby. */
    private val alerted = HashMap<String, Long>()
    private var lastAlert = 0L
    private var lastSummary = -1

    @Volatile
    private var closing = false

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
        startForeground(NOTIFICATION_ID, ongoing(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) {
            val app = application as AntApplication
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = sc
            GlassesGuard.setRunning(true)
            var latest: List<BleDevice> = emptyList()
            // A low-power scan with an empty filter: it keeps running with the screen off, and glasses advertise often.
            job = sc.launch {
                launch { app.bluetooth.bleScan(lowPower = true, staleMs = 30_000, filters = listOf(ScanFilter.Builder().build())).collect { latest = it } }
                while (isActive) {
                    GlassesGuard.watch.update(latest, System.currentTimeMillis())
                    update()
                    delay(2_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        GlassesGuard.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update() {
        if (closing) return
        val now = System.currentTimeMillis()
        val near = GlassesGuard.watch.nearby(now).filter { it.hit.confidence != GlassesConfidence.Low }
        if (near.size != lastSummary) {
            lastSummary = near.size
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing(near.size))
        }
        if (GlassesGuard.watching) { near.forEach { alerted[it.hit.address] = now }; return }
        val fresh = near.filter { s -> alerted[s.hit.address]?.let { now - it > 10 * 60_000L } ?: true }
        if (fresh.isEmpty() || now - lastAlert < 60_000L) return
        fresh.forEach { alerted[it.hit.address] = now }
        lastAlert = now
        val h = fresh.maxBy { it.hit.rssi }.hit
        alert(S(R.string.glasses_alert_title), S(R.string.glasses_alert_text, h.product, "%.0f".format(h.distanceM)))
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 18, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_GLASSES),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(near: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.glasses_guard), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 19, Intent(this, GlassesGuardService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.glasses_guard_running))
            .setContentText(if (near > 0) S(R.string.glasses_nearby_now, near) else S(R.string.glasses_none_now))
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    private fun alert(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, S(R.string.glasses_alerts), NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        nm.notify(ALERT_ID, n)
    }

    companion object {
        private const val CHANNEL = "glasses_guard"
        private const val ALERT_CHANNEL = "glasses_alerts"
        private const val NOTIFICATION_ID = 47
        private const val ALERT_ID = 22_000
        const val ACTION_STOP = "com.allnetworktools.GLASSES_GUARD_STOP"
    }
}
