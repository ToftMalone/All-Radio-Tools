package com.allnetworktools.data.bitchat

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
import com.allnetworktools.MainActivity
import com.allnetworktools.R
import com.allnetworktools.util.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

/** Keys and nickname survive restarts so the peer ID stays the same, like on the official apps. */
class BitchatStore(context: Context) {
    private val prefs = context.getSharedPreferences("bitchat", Context.MODE_PRIVATE)

    fun identity(): Identity {
        val noise = prefs.getString("noise", null)?.let(::unhexKey)
        val sign = prefs.getString("sign", null)?.let(::unhexKey)
        if (noise != null && sign != null) return Identity(noise, sign)
        return Identity.generate().also { id ->
            prefs.edit().putString("noise", hexKey(id.noisePrivate)).putString("sign", hexKey(id.signingPrivate)).apply()
        }
    }

    /** Panic button: forget the keys, which gives this device a brand-new peer ID. */
    fun reset() {
        prefs.edit().remove("noise").remove("sign").apply()
    }

    var nickname: String
        get() = prefs.getString("nick", null) ?: ("anon" + (1000 + SecureRandom().nextInt(9000))).also { prefs.edit().putString("nick", it).apply() }
        set(v) { prefs.edit().putString("nick", v).apply() }

    private fun hexKey(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhexKey(s: String) = if (s.length == 64) ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } else null
}

/** Bridge between the foreground service that owns the mesh and the screens that show it. */
object BitchatHub {
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val _ble = MutableStateFlow(BleStatus.Idle)
    val ble: StateFlow<BleStatus> = _ble.asStateFlow()

    @Volatile
    internal var mesh: BitchatMesh? = null

    internal fun publish(s: MeshState) { _state.value = s }
    internal fun bleStatus(s: BleStatus) { _ble.value = s }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, BitchatService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, BitchatService::class.java).setAction(BitchatService.ACTION_STOP))
    }

    fun sendPublic(text: String) { mesh?.sendPublic(text) }
    fun sendPrivate(peer: String, text: String) { mesh?.sendPrivate(peer, text) }
    fun openChat(peer: String?) { mesh?.openChat(peer) }
    fun setNickname(context: Context, name: String) {
        val n = name.trim().take(40)
        if (n.isNotEmpty()) { BitchatStore(context).nickname = n; mesh?.setNickname(n); if (mesh == null) _state.value = _state.value.copy(nickname = n) }
    }
    fun nickname(context: Context): String = BitchatStore(context).nickname
}

/** Foreground service keeping the mesh alive: relays packets for other phones and receives messages in the background. */
class BitchatService : Service() {
    private var scope: CoroutineScope? = null
    private var transport: BleMeshTransport? = null
    private var mesh: BitchatMesh? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(MeshState()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (mesh == null) {
            val store = BitchatStore(this)
            val identity = store.identity()
            val t = BleMeshTransport(this, identity.peerId)
            val m = BitchatMesh(identity, store.nickname, t)
            t.mesh = m
            transport = t; mesh = m
            BitchatHub.mesh = m
            val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            scope = sc
            m.start()
            t.start()
            sc.launch { m.state.collect { s -> BitchatHub.publish(s); notify(s) } }
            sc.launch { t.status.collect { BitchatHub.bleStatus(it) } }
            sc.launch { while (true) { delay(10_000); m.tick() } }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mesh?.stop()
        transport?.stop()
        scope?.cancel(); scope = null
        BitchatHub.mesh = null
        BitchatHub.publish(BitchatHub.state.value.copy(running = false, links = 0, peers = emptyList()))
        BitchatHub.bleStatus(BleStatus.Idle)
        mesh = null; transport = null
        super.onDestroy()
    }

    private var lastNotified = ""

    private fun notify(s: MeshState) {
        val key = "${s.peers.size}/${s.links}"
        if (key == lastNotified) return
        lastNotified = key
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(s))
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun notification(s: MeshState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, S(R.string.bitchat), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BitchatService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S(R.string.bitchat_mesh_running))
            .setContentText(S(R.string.bitchat_notification_text, s.peers.size, s.links))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, S(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "bitchat"
        private const val NOTIFICATION_ID = 43
        const val ACTION_STOP = "com.allnetworktools.BITCHAT_STOP"
    }
}
