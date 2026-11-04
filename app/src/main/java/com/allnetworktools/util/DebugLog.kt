package com.allnetworktools.util

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * Verbose debug journal kept on the phone, for finding a bug afterwards. It never leaves the device by itself: the user
 * shares it, saves it or clears it from the settings.
 *
 * Two files take turns (the current one and the previous one), each capped at [MaxFileBytes], so the journal never
 * exceeds [MaxBytes] (50 MB). When the current file is full it becomes the previous one and the oldest lines go.
 * Besides what the app writes itself, the system log of the app's own process is copied in (lines starting with "LC").
 */
object DebugLog {
    const val MaxBytes = 50L * 1024 * 1024
    private const val MaxFileBytes = MaxBytes / 2
    private const val QueueCapacity = 50_000

    private val lock = Any()
    private val queue = LinkedBlockingQueue<String>(QueueCapacity)
    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    private var current: File? = null
    private var previous: File? = null
    private var currentSize = 0L
    private var out: OutputStream? = null
    @Volatile private var dropped = 0
    @Volatile private var started = false

    private fun stamp(): String = clock.get()!!.format(Date())

    fun init(context: Context) {
        if (started) return
        started = true
        val dir = File(context.filesDir, "debug").apply { mkdirs() }
        // A shared copy left in the cache by an earlier run is not needed any more.
        File(context.cacheDir, "share").deleteRecursively()
        synchronized(lock) {
            current = File(dir, "journal.log")
            previous = File(dir, "journal.old.log")
            currentSize = current!!.length()
            open()
        }
        Thread({ drain() }, "debug-log").apply { isDaemon = true; start() }
        header(context)
        captureSystemLog()
        installCrashHandler()
    }

    private fun header(context: Context) {
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        i("App", "==== new session · version $version · pid ${Process.myPid()} ====")
        i("App", "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}")
        i("App", "Locale: ${Locale.getDefault()} · density ${context.resources.displayMetrics.density} · ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
        i("App", "Features: " + listOf(
            "wifi" to "android.hardware.wifi", "bluetooth_le" to "android.hardware.bluetooth_le", "telephony" to "android.hardware.telephony",
            "gps" to "android.hardware.location.gps", "nfc" to "android.hardware.nfc", "usb_host" to "android.hardware.usb.host",
            "wifi_aware" to "android.hardware.wifi.aware", "wifi_rtt" to "android.hardware.wifi.rtt",
        ).joinToString(" ") { (n, f) -> "$n=" + pm.hasSystemFeature(f) })
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                append("${stamp()} F/Crash [${t.name}] uncaught exception\n${android.util.Log.getStackTraceString(e)}\n")
            }
            previousHandler?.uncaughtException(t, e)
        }
    }

    /** Copies the system log of this process into the journal, as long as the app lives. */
    private fun captureSystemLog() {
        Thread({
            runCatching {
                val p = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { enqueue("LC $it") } }
            }.onFailure { w("App", "System log capture unavailable: ${it.message}") }
        }, "debug-logcat").apply { isDaemon = true; start() }
    }

    // ---- writing -----------------------------------------------------------------------------------

    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = log('W', tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = log('E', tag, msg, e)

    private fun log(level: Char, tag: String, msg: String, e: Throwable?) {
        val line = buildString {
            append(stamp()).append(' ').append(level).append('/').append(tag).append(" [").append(Thread.currentThread().name).append("] ").append(msg)
            if (e != null) append('\n').append(android.util.Log.getStackTraceString(e).trimEnd())
        }
        enqueue(line)
    }

    private fun enqueue(line: String) {
        if (!started) return
        if (!queue.offer(line)) dropped++
    }

    private fun drain() {
        val batch = ArrayList<String>(512)
        while (true) {
            runCatching {
                batch.add(queue.take())
                queue.drainTo(batch, 2_000)
                val d = dropped
                if (d > 0) { dropped = 0; batch.add("${stamp()} W/DebugLog [debug-log] $d lines dropped (queue full)") }
                val text = StringBuilder()
                for (l in batch) text.append(l).append('\n')
                append(text.toString())
                batch.clear()
                // Let the writer breathe if the system log is flooding.
                Thread.sleep(150)
            }.onFailure { batch.clear() }
        }
    }

    private fun open() {
        out = runCatching { java.io.BufferedOutputStream(java.io.FileOutputStream(current!!, true), 64 * 1024) }.getOrNull()
    }

    private fun append(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val f = current ?: return
            if (currentSize + bytes.size > MaxFileBytes) rotate(f)
            out?.write(bytes)
            // Written through at once so a crash or a kill loses nothing.
            out?.flush()
            currentSize += bytes.size
        }
    }

    private fun rotate(f: File) {
        runCatching { out?.close() }
        previous?.delete()
        f.renameTo(previous!!)
        currentSize = 0
        open()
    }

    // ---- reading and managing ------------------------------------------------------------------------

    /** Size of the journal on the phone, in bytes. */
    fun size(): Long = synchronized(lock) { (previous?.takeIf { it.exists() }?.length() ?: 0L) + (current?.length() ?: 0L) }

    /** Writes the whole journal, oldest line first. */
    fun writeTo(dest: OutputStream) {
        synchronized(lock) {
            runCatching { out?.flush() }
            for (f in listOfNotNull(previous, current)) {
                if (f.exists()) f.inputStream().use { it.copyTo(dest) }
            }
        }
    }

    /** A copy of the journal in the cache folder, ready to be shared as a file. */
    fun shareCopy(context: Context): File {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "all-radio-tools-debug-${Export.stamp(System.currentTimeMillis())}.log")
        f.outputStream().use { writeTo(it) }
        return f
    }

    /** Erases the journal; a first line says when and the journal goes on. */
    fun clear() {
        synchronized(lock) {
            runCatching { out?.close() }
            previous?.delete()
            current?.delete()
            currentSize = 0
            open()
        }
        i("App", "Journal cleared by the user")
    }
}
