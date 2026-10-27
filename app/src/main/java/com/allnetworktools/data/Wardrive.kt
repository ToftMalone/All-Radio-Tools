package com.allnetworktools.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** What a sighting is, with its letter in the database and its name in a WiGLE file. */
enum class NetType(val code: String, val wigle: String) { Wifi("W", "WIFI"), Ble("E", "BLE") }

/** One network heard at one place. */
data class NetSighting(
    val mac: String,
    val name: String,
    val frequency: Int,
    val caps: String,
    val type: NetType,
    val level: Int,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val accuracy: Float,
    val timeMs: Long,
)

/**
 * Addresses left out of the list and of the database: whole addresses ("AA:BB:CC:DD:EE:FF") or a maker's prefix
 * ("AA:BB:CC", the OUI). Written with colons, dashes, dots or nothing, in any case.
 */
class Exclusions(entries: Collection<String>) {
    private val macs = HashSet<String>()
    private val prefixes = HashSet<String>()

    init {
        for (e in entries) {
            val h = normalize(e) ?: continue
            if (h.length == 12) macs += h else prefixes += h
        }
    }

    val isEmpty get() = macs.isEmpty() && prefixes.isEmpty()

    fun excludes(mac: String): Boolean {
        if (isEmpty) return false
        val h = hex(mac)
        return h in macs || (h.length >= 6 && h.substring(0, 6) in prefixes)
    }

    companion object {
        private fun hex(s: String) = buildString { for (c in s) if (c.isLetterOrDigit()) append(c.uppercaseChar()) }

        /** The 6 (prefix) or 12 (address) hex digits of an entry, or null when it is neither. */
        fun normalize(entry: String): String? {
            val h = hex(entry)
            return h.takeIf { (it.length == 6 || it.length == 12) && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
        }

        /** How an entry is shown and stored: "AA:BB:CC" or "AA:BB:CC:DD:EE:FF". */
        fun pretty(entry: String): String? = normalize(entry)?.chunked(2)?.joinToString(":")
    }
}

/** The IEEE register of 24-bit prefixes (MA-L), from assets/oui.tsv.gz (built by scripts/fetch_oui.py). */
class OuiTable(private val load: () -> java.io.InputStream?) {
    private val table: Map<Int, String> by lazy {
        val m = HashMap<Int, String>(48_000)
        runCatching {
            load()?.let { GZIPInputStream(it) }?.bufferedReader()?.useLines { lines ->
                for (l in lines) {
                    val tab = l.indexOf('\t')
                    if (tab == 6) m[l.substring(0, 6).toInt(16)] = l.substring(7)
                }
            }
        }
        m
    }

    /** The maker of a universally administered address; null for a random or locally administered one. */
    fun vendor(mac: String): String? {
        val h = mac.filter { it.isLetterOrDigit() }
        if (h.length < 6) return null
        val p = h.substring(0, 6).toIntOrNull(16) ?: return null
        if ((p shr 16) and 0x02 != 0) return null
        return table[p]?.let(::tidy)
    }

    companion object {
        fun of(context: Context) = OuiTable { runCatching { context.assets.open("oui.tsv.gz") }.getOrNull() }

        /** "FREEBOX SAS" reads "Freebox Sas", as WiGLE writes it; names already in mixed case stay as they are. */
        fun tidy(name: String): String =
            if (name.any { it.isLowerCase() }) name
            else name.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}

/** The WiGLE CSV file (format 1.6) the website takes in. */
object WigleCsv {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun header(appVersion: String): String =
        "WigleWifi-1.6,appRelease=$appVersion,model=${clean(Build.MODEL)},release=${clean(Build.VERSION.RELEASE)}," +
            "device=${clean(Build.DEVICE)},display=${clean(Build.DISPLAY)},board=${clean(Build.BOARD)},brand=${clean(Build.BRAND)}," +
            "star=Sol,body=3,subBody=0\n" +
            "MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type\n"

    private fun clean(s: String?) = s.orEmpty().replace(',', ' ')

    /** A field with a comma, a quote or a line break goes between quotes, its quotes doubled. */
    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(s: NetSighting): String = buildString {
        append(s.mac).append(',')
        append(escape(s.name)).append(',')
        append(escape(if (s.type == NetType.Ble) "Misc [LE]" else s.caps)).append(',')
        synchronized(time) { append(time.format(Date(s.timeMs))) }.append(',')
        // Bluetooth has no channel; WiGLE keeps its device class where Wi-Fi has the frequency (7936: uncategorised).
        append(if (s.type == NetType.Wifi) channelOf(s.frequency) else 0).append(',')
        append(if (s.type == NetType.Wifi) s.frequency else 7936).append(',')
        append(s.level).append(',')
        append(String.format(Locale.US, "%.8f,%.8f,%.1f,%.1f", s.lat, s.lon, s.altitude, s.accuracy)).append(',')
        append(",,")
        append(s.type.wigle).append('\n')
    }
}

/** Counts of networks in the database. */
data class DbCounts(val wifi: Long = 0, val ble: Long = 0) { val total get() = wifi + ble }

/**
 * Every network met (its best place: where it was heard strongest) and every sighting (each place it was heard),
 * as the WiGLE app keeps them. Stays on the phone until the user sends it.
 */
class WardriveDb(context: Context, name: String? = "wardriving.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE network (bssid TEXT PRIMARY KEY, ssid TEXT NOT NULL, frequency INTEGER NOT NULL, capabilities TEXT NOT NULL, " +
                "type TEXT NOT NULL, lasttime INTEGER NOT NULL, lastlat REAL NOT NULL, lastlon REAL NOT NULL, " +
                "bestlevel INTEGER NOT NULL, bestlat REAL NOT NULL, bestlon REAL NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE location (_id INTEGER PRIMARY KEY AUTOINCREMENT, bssid TEXT NOT NULL, level INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, altitude REAL NOT NULL, accuracy REAL NOT NULL, time INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Writes the sightings; returns how many networks were new, per type. */
    @Synchronized
    fun write(list: List<NetSighting>): Map<NetType, Int> {
        val fresh = HashMap<NetType, Int>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in list) {
                val best = db.rawQuery("SELECT bestlevel FROM network WHERE bssid = ?", arrayOf(s.mac)).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
                val v = ContentValues().apply {
                    put("ssid", s.name); put("frequency", s.frequency); put("capabilities", s.caps); put("type", s.type.code)
                    put("lasttime", s.timeMs); put("lastlat", s.lat); put("lastlon", s.lon)
                    if (best == null || s.level > best) { put("bestlevel", s.level); put("bestlat", s.lat); put("bestlon", s.lon) }
                }
                if (best == null) {
                    v.put("bssid", s.mac)
                    db.insert("network", null, v)
                    fresh[s.type] = (fresh[s.type] ?: 0) + 1
                } else {
                    // A hidden network keeps the name it was once heard with.
                    if (s.name.isEmpty()) v.remove("ssid")
                    db.update("network", v, "bssid = ?", arrayOf(s.mac))
                }
                db.insert(
                    "location", null,
                    ContentValues().apply {
                        put("bssid", s.mac); put("level", s.level); put("lat", s.lat); put("lon", s.lon)
                        put("altitude", s.altitude); put("accuracy", s.accuracy.toDouble()); put("time", s.timeMs)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    @Synchronized
    fun counts(): DbCounts = readableDatabase.rawQuery("SELECT type, COUNT(*) FROM network GROUP BY type", null).use { c ->
        var w = 0L
        var b = 0L
        while (c.moveToNext()) when (c.getString(0)) { NetType.Wifi.code -> w = c.getLong(1); NetType.Ble.code -> b = c.getLong(1) }
        DbCounts(w, b)
    }

    @Synchronized
    fun lastSightingId(): Long = readableDatabase.rawQuery("SELECT MAX(_id) FROM location", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    @Synchronized
    fun sightingsAfter(id: Long): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM location WHERE _id > ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    /** Writes the sightings after [afterId] as WiGLE CSV rows; returns the last id written (or [afterId]). */
    @Synchronized
    fun exportCsv(afterId: Long, out: Writer): Long {
        var last = afterId
        readableDatabase.rawQuery(
            "SELECT l._id, l.bssid, n.ssid, n.frequency, n.capabilities, n.type, l.level, l.lat, l.lon, l.altitude, l.accuracy, l.time " +
                "FROM location l JOIN network n ON n.bssid = l.bssid WHERE l._id > ? ORDER BY l._id",
            arrayOf(afterId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val type = if (c.getString(5) == NetType.Ble.code) NetType.Ble else NetType.Wifi
                out.write(WigleCsv.row(NetSighting(c.getString(1), c.getString(2), c.getInt(3), c.getString(4), type, c.getInt(6), c.getDouble(7), c.getDouble(8), c.getDouble(9), c.getFloat(10), c.getLong(11))))
                last = c.getLong(0)
            }
        }
        return last
    }

    /** Deletes what [ex] now leaves out; returns the number of networks removed. */
    @Synchronized
    fun purge(ex: Exclusions): Int {
        if (ex.isEmpty) return 0
        val db = writableDatabase
        val gone = db.rawQuery("SELECT bssid FROM network", null).use { c -> buildList { while (c.moveToNext()) c.getString(0).takeIf(ex::excludes)?.let(::add) } }
        if (gone.isEmpty()) return 0
        db.beginTransaction()
        try {
            for (m in gone) {
                db.delete("network", "bssid = ?", arrayOf(m))
                db.delete("location", "bssid = ?", arrayOf(m))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return gone.size
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("network", null, null)
        writableDatabase.delete("location", null, null)
    }
}

/** The WiGLE account (API name and token from wigle.net/account) and the exclusion list. */
data class WardriveSettings(
    val apiName: String = "",
    val apiToken: String = "",
    /** The WiGLE user name, once the account has been checked. */
    val user: String = "",
    val exclusions: Set<String> = emptySet(),
    /** Last sighting sent to WiGLE: the next upload starts after it. */
    val uploadedUpTo: Long = 0,
) {
    val connected get() = apiName.isNotEmpty() && apiToken.isNotEmpty()
}

private val Context.wardriveStore: DataStore<Preferences> by preferencesDataStore("wardriving")

class WardriveSettingsRepository(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("wigle_api_name")
        val token = stringPreferencesKey("wigle_api_token")
        val user = stringPreferencesKey("wigle_user")
        val excl = stringSetPreferencesKey("exclusions")
        val upTo = longPreferencesKey("uploaded_up_to")
    }

    val settings: Flow<WardriveSettings> = context.wardriveStore.data.map { p ->
        WardriveSettings(p[K.name].orEmpty(), p[K.token].orEmpty(), p[K.user].orEmpty(), p[K.excl].orEmpty(), p[K.upTo] ?: 0L)
    }

    suspend fun setAccount(name: String, token: String, user: String) = context.wardriveStore.edit { it[K.name] = name; it[K.token] = token; it[K.user] = user }
    suspend fun disconnect() = context.wardriveStore.edit { it.remove(K.name); it.remove(K.token); it.remove(K.user) }
    suspend fun setExclusions(v: Set<String>) = context.wardriveStore.edit { it[K.excl] = v }
    suspend fun setUploadedUpTo(id: Long) = context.wardriveStore.edit { it[K.upTo] = id }
}

/** The WiGLE API (api.wigle.net), with the account's API name and token as Basic credentials. */
object WigleClient {
    private const val BASE = "https://api.wigle.net/api/v2"

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val message: String) : Result<Nothing>
    }

    private fun auth(name: String, token: String) = "Basic " + Base64.encodeToString("$name:$token".toByteArray(), Base64.NO_WRAP)

    private fun message(c: HttpURLConnection): String {
        val body = runCatching { (c.errorStream ?: c.inputStream)?.bufferedReader()?.readText() }.getOrNull()
        val m = body?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }?.takeIf { it.isNotBlank() }
        return m ?: "HTTP ${c.responseCode}"
    }

    /** Checks the credentials; returns the WiGLE user name. */
    fun profile(name: String, token: String): Result<String> = runCatching {
        val c = URL("$BASE/profile/user").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Authorization", auth(name, token))
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) return@runCatching Result.Failed(message(c))
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            if (!j.optBoolean("success", false)) Result.Failed(j.optString("message", "WiGLE")) else Result.Ok(j.optString("userid").ifEmpty { name })
        } finally {
            c.disconnect()
        }
    }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }

    /** Sends a gzipped CSV file; returns WiGLE's answer. */
    fun upload(name: String, token: String, fileName: String, gz: ByteArray): Result<String> = runCatching {
        val boundary = "----AllRadioTools" + System.nanoTime()
        val c = URL("$BASE/file/upload").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("Authorization", auth(name, token))
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: application/gzip\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        c.setFixedLengthStreamingMode(head.length + gz.size + tail.length)
        try {
            c.outputStream.use { o -> o.write(head.toByteArray()); o.write(gz); o.write(tail.toByteArray()) }
            if (c.responseCode !in 200..299) return@runCatching Result.Failed(message(c))
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            if (!j.optBoolean("success", false)) Result.Failed(j.optString("message", "WiGLE")) else Result.Ok(j.optString("message"))
        } finally {
            c.disconnect()
        }
    }.getOrElse { Result.Failed(it.message ?: it.javaClass.simpleName) }

    /** The gzipped CSV of the sightings after [afterId], with the last id it holds (null when there is none). */
    fun build(db: WardriveDb, afterId: Long, appVersion: String): Pair<ByteArray, Long>? {
        val bytes = ByteArrayOutputStream()
        var last: Long
        GZIPOutputStream(bytes).bufferedWriter().use { w ->
            w.write(WigleCsv.header(appVersion))
            last = db.exportCsv(afterId, w)
        }
        return if (last > afterId) bytes.toByteArray() to last else null
    }
}
