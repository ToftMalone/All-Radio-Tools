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
import android.os.SystemClock
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

/**
 * The BLE spam session lives for the whole process, so the background engine and the screen share it: open the tool while
 * the engine runs and you see what it caught on the way.
 */
object BleSpamGuard {
    val watch = BleSpamWatch()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    internal fun setRunning(v: Boolean) { _running.value = v }

    /** The tool is on screen: no alert for what the user is already looking at. */
    @Volatile
    var watching = false

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, BleSpamGuardService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, BleSpamGuardService::class.java).setAction(BleSpamGuardService.ACTION_STOP))
    }
}

/** Foreground service feeding the BLE spam session with the app closed, and alerting on an attack or a Flipper Zero. */
@SuppressLint("MissingPermission")
class BleSpamGuardService : Service() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val alertedIncidents = HashSet<Pair<SpamKind, Long>>()
    private val alertedFlippers = HashSet<String>()
    private var lastSummary = ""

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
        startForeground(NOTIFICATION_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job == null) {
            val app = application as AntApplication
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = sc
            BleSpamGuard.setRunning(true)
            var latest: List<BleDevice> = emptyList()
            // An empty filter keeps the scan going with the screen off, which an unfiltered scan does not. Spam bursts
            // are short, so the scan runs at full speed: this costs battery while the engine is on.
            job = sc.launch {
                launch { app.bluetooth.bleScan(lowPower = false, staleMs = 15_000, filters = listOf(ScanFilter.Builder().build())).collect { latest = it } }
                while (isActive) {
                    BleSpamGuard.watch.update(latest, SystemClock.elapsedRealtime(), System.currentTimeMillis())
                    update()
                    delay(1_000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        closing = true
        job?.cancel(); job = null
        scope?.cancel(); scope = null
        BleSpamGuard.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun update() {
        if (closing) return
        val w = BleSpamGuard.watch
        val attacks = w.kinds.filter { it.attacking }
        val summary = attacks.joinToString { it.kind.name } + "/" + w.flippers.size
        if (summary != lastSummary) {
            lastSummary = summary
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, ongoing())
        }
        if (BleSpamGuard.watching) {
            // On screen: what is shown counts as seen.
            w.incidents.forEach { alertedIncidents += it.kind to it.startMs }
            w.flippers.forEach { alertedFlippers += it.address }
            return
        }
        w.incidents.filter { it.endMs == null }.forEach { i ->
            if (alertedIncidents.add(i.kind to i.startMs)) alert(S(R.string.blespam_alert_title), S(R.string.blespam_alert_text, i.kind.label, i.kind.target), i.kind.ordinal)
        }
        w.flippers.forEach { f ->
            if (alertedFlippers.add(f.address)) alert(S(R.string.blespam_flipper_alert_title), S(R.string.blespam_flipper_alert_text, f.name ?: "Flipper Zero", f.rssi), 100 + (f.address.hashCode() and 0xFF))
        }
    }

    private fun openIntent() = PendingIntent.getActivity(
        this, 16, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_BLE_SPAM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("LaunchActivityFromNotification")
    private fun ongoing(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.blespam_guard), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 17, Intent(this, BleSpamGuardService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val w = BleSpamGuard.watch
        val attacks = w.kinds.filter { it.attacking }
        val text = when {
            attacks.isNotEmpty() -> S(R.string.blespam_spam_now) + " · " + attacks.joinToString { it.kind.label }
            else -> S(R.string.blespam_quiet)
        } + if (w.flippers.isNotEmpty()) " · " + S(R.string.blespam_flipper_nearby, w.flippers.size) else ""
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.blespam_guard_running))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    private fun alert(title: String, text: String, id: Int) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, S(R.string.blespam_alerts), NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent())
            .build()
        nm.notify(ALERT_BASE + id, n)
    }

    companion object {
        private const val CHANNEL = "ble_spam_guard"
        private const val ALERT_CHANNEL = "ble_spam_alerts"
        private const val NOTIFICATION_ID = 46
        private const val ALERT_BASE = 21_000
        const val ACTION_STOP = "com.allnetworktools.BLE_SPAM_GUARD_STOP"
    }
}
