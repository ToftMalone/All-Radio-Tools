package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import com.allnetworktools.R
import com.allnetworktools.util.S

/** An AX.25 UI frame: who sent it, to whom, via which repeaters, and the payload. */
class Ax25Frame(val src: String, val dst: String, val path: List<String>, val info: ByteArray)

object Ax25 {
    private fun address(b: ByteArray, o: Int): String {
        val call = String(CharArray(6) { ((b[o + it].toInt() and 0xFF) shr 1).toChar() }).trim()
        val ssid = (b[o + 6].toInt() shr 1) and 0x0F
        val repeated = b[o + 6].toInt() and 0x80 != 0
        return call + (if (ssid != 0) "-$ssid" else "") + (if (repeated) "*" else "")
    }

    /** Frame bytes without the FCS; null unless it is a UI frame with the APRS protocol id. */
    fun parse(b: ByteArray): Ax25Frame? {
        if (b.size < 16) return null
        var o = 14
        while (o + 7 <= b.size && b[o - 1].toInt() and 1 == 0) o += 7
        if (b[o - 1].toInt() and 1 == 0 || o + 2 > b.size) return null
        if ((b[o].toInt() and 0xFF) != 0x03 || (b[o + 1].toInt() and 0xFF) != 0xF0) return null // UI frame, no layer 3
        val path = ArrayList<String>()
        var p = 14
        while (p < o) { path += address(b, p); p += 7 }
        val src = address(b, 7).trimEnd('*')
        return Ax25Frame(src, address(b, 0).trimEnd('*'), path, b.copyOfRange(o + 2, b.size))
    }
}

data class AprsWeather(
    val windDir: Int?, val windMph: Int?, val gustMph: Int?, val tempF: Int?, val humidity: Int?, val pressureHpa: Double?,
)

/** One decoded APRS beacon. Person-to-person messages are recognised but their text is never kept. */
data class AprsReport(
    val src: String,
    val path: List<String>,
    val kind: Kind,
    /** Object or item name, when the beacon describes something other than its sender. */
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val speedKn: Double? = null,
    val courseDeg: Int? = null,
    val altitudeM: Double? = null,
    val symbol: String? = null,
    val comment: String? = null,
    val weather: AprsWeather? = null,
    val killed: Boolean = false,
) {
    enum class Kind { Position, MicE, Object, Item, Weather, Status, Message }
}

/** Automatic Packet Reporting System (APRS 1.0.1): position, Mic-E, object, item, weather and status reports. */
object Aprs {
    private fun lat(d: Int, m: Double, north: Boolean) = (d + m / 60) * (if (north) 1 else -1)

    private fun digits(s: String) = s.replace(' ', '0')

    /** "DDMM.mmN" */
    private fun parseLat(s: String): Double? {
        if (s.length != 8) return null
        val d = digits(s.substring(0, 2)).toIntOrNull() ?: return null
        val m = digits(s.substring(2, 7)).toDoubleOrNull() ?: return null
        val v = when (s[7]) { 'N' -> lat(d, m, true); 'S' -> lat(d, m, false); else -> return null }
        return v.takeIf { it in -90.0..90.0 }
    }

    /** "DDDMM.mmE" */
    private fun parseLon(s: String): Double? {
        if (s.length != 9) return null
        val d = digits(s.substring(0, 3)).toIntOrNull() ?: return null
        val m = digits(s.substring(3, 8)).toDoubleOrNull() ?: return null
        val v = when (s[8]) { 'E' -> d + m / 60; 'W' -> -(d + m / 60); else -> return null }
        return v.takeIf { it in -180.0..180.0 }
    }

    private fun b91(s: String): Int = s.fold(0) { a, c -> a * 91 + (c.code - 33) }

    private class Pos(val lat: Double, val lon: Double, val symbol: String, val course: Int?, val speedKn: Double?, val altM: Double?, val rest: String)

    /** A position at [i]: uncompressed ("4903.50N/07201.75W>") or compressed (base-91). */
    private fun position(s: String, i: Int): Pos? {
        if (i >= s.length) return null
        if (s[i].isDigit() || s[i] == ' ') {
            if (s.length < i + 19) return null
            val la = parseLat(s.substring(i, i + 8)) ?: return null
            val lo = parseLon(s.substring(i + 9, i + 18)) ?: return null
            return Pos(la, lo, "${s[i + 8]}${s[i + 18]}", null, null, null, s.substring(i + 19))
        }
        if (s.length < i + 13) return null
        val la = 90 - b91(s.substring(i + 1, i + 5)) / 380_926.0
        val lo = -180 + b91(s.substring(i + 5, i + 9)) / 190_463.0
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        val c = s[i + 10].code - 33
        val sp = s[i + 11].code - 33
        val t = s[i + 12].code - 33
        var course: Int? = null
        var speed: Double? = null
        var alt: Double? = null
        if (t and 0x18 == 0x10) alt = 1.002.pow(c * 91 + sp) * 0.3048
        else if (c in 0..89) { course = c * 4; speed = 1.08.pow(sp) - 1 }
        return Pos(la, lo, "${s[i]}${s[i + 9]}", course, speed, alt, s.substring(i + 13))
    }

    private fun weather(s: String): AprsWeather? {
        fun field(tag: Char, width: Int): Int? {
            val k = s.indexOf(tag)
            if (k < 0 || k + 1 + width > s.length) return null
            return s.substring(k + 1, k + 1 + width).toIntOrNull()
        }
        // Wind direction and speed may open the block as "220/004".
        val lead = Regex("^(\\d{3})/(\\d{3})").find(s)
        val dir = lead?.groupValues?.get(1)?.toIntOrNull() ?: field('c', 3)
        val wind = lead?.groupValues?.get(2)?.toIntOrNull() ?: field('s', 3)
        val gust = field('g', 3)
        val t = field('t', 3)
        val h = field('h', 2)?.let { if (it == 0) 100 else it }
        val b = field('b', 5)?.let { it / 10.0 }
        if (dir == null && wind == null && t == null && h == null && b == null) return null
        return AprsWeather(dir, wind, gust, t, h, b)
    }

    private fun altitude(comment: String): Double? =
        Regex("/A=(-?\\d{6})").find(comment)?.groupValues?.get(1)?.toIntOrNull()?.let { it * 0.3048 }

    private fun clean(s: String) = s.filter { it.code in 32..126 || it.code >= 160 }.trim().takeIf { it.isNotEmpty() }

    fun decode(f: Ax25Frame): AprsReport? {
        val s = String(f.info, Charsets.ISO_8859_1)
        if (s.isEmpty()) return null
        fun report(kind: AprsReport.Kind, p: Pos?, name: String? = null, killed: Boolean = false): AprsReport {
            var course = p?.course
            var speed = p?.speedKn
            var rest = p?.rest ?: ""
            var wx: AprsWeather? = null
            if (p != null && Regex("^\\d{3}/\\d{3}").containsMatchIn(rest)) {
                if (p.symbol.endsWith("_")) wx = weather(rest)
                else { course = rest.substring(0, 3).toInt().takeIf { it in 1..360 }; speed = rest.substring(4, 7).toInt().toDouble(); }
                rest = rest.substring(7)
            } else if (p != null && p.symbol.endsWith("_")) wx = weather(rest)
            return AprsReport(
                f.src, f.path, if (wx != null && kind == AprsReport.Kind.Position) AprsReport.Kind.Weather else kind, name,
                p?.lat, p?.lon, speed, course, p?.altM ?: altitude(rest), p?.symbol, if (wx != null) null else clean(rest), wx, killed,
            )
        }
        return when (s[0]) {
            '!', '=' -> report(AprsReport.Kind.Position, position(s, 1) ?: return null)
            '/', '@' -> report(AprsReport.Kind.Position, position(s, 8) ?: return null)
            '`', '\'' -> micE(f, s)
            ';' -> {
                if (s.length < 19) return null
                report(AprsReport.Kind.Object, position(s, 18) ?: return null, s.substring(1, 10).trim(), killed = s[10] == '_')
            }
            ')' -> {
                val e = s.indexOfAny(charArrayOf('!', '_'), 1)
                if (e !in 4..10) return null
                report(AprsReport.Kind.Item, position(s, e + 1) ?: return null, s.substring(1, e).trim(), killed = s[e] == '_')
            }
            '_' -> AprsReport(f.src, f.path, AprsReport.Kind.Weather, weather = weather(s.drop(9)) ?: return null)
            '>' -> AprsReport(f.src, f.path, AprsReport.Kind.Status, comment = clean(s.substring(1)))
            ':' -> AprsReport(f.src, f.path, AprsReport.Kind.Message) // heard, never read
            else -> null
        }
    }

    /** Mic-E: the latitude hides in the destination address, the rest in the first bytes of the payload. */
    private fun micE(f: Ax25Frame, s: String): AprsReport? {
        val dst = f.dst
        if (dst.length < 6 || s.length < 9) return null
        val d = IntArray(6)
        for (i in 0 until 6) {
            val c = dst[i]
            d[i] = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'J' -> c - 'A'
                in 'P'..'Y' -> c - 'P'
                'K', 'L', 'Z' -> 0
                else -> return null
            }
        }
        val north = dst[3] in 'P'..'Z'
        val offset = dst[4] in 'P'..'Z'
        val west = dst[5] in 'P'..'Z'
        val la = lat(d[0] * 10 + d[1], (d[2] * 10 + d[3]) + (d[4] * 10 + d[5]) / 100.0, north)
        var deg = s[1].code - 28
        if (offset) deg += 100
        if (deg in 180..189) deg -= 80 else if (deg in 190..199) deg -= 190
        var min = s[2].code - 28
        if (min >= 60) min -= 60
        val hund = s[3].code - 28
        val lo = (deg + (min + hund / 100.0) / 60) * (if (west) -1 else 1)
        var sp = (s[4].code - 28) * 10 + (s[5].code - 28) / 10
        var course = ((s[5].code - 28) % 10) * 100 + (s[6].code - 28)
        if (sp >= 800) sp -= 800
        if (course >= 400) course -= 400
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || sp !in 0..799) return null
        var rest = s.substring(9)
        var alt: Double? = null
        val k = rest.indexOf('}')
        if (k >= 3) {
            alt = (b91(rest.substring(k - 3, k)) - 10_000).toDouble()
            rest = rest.removeRange(k - 3, k + 1)
        }
        return AprsReport(
            f.src, f.path, AprsReport.Kind.MicE, null, la, lo, sp.toDouble(), course.takeIf { it in 1..360 }, alt ?: altitude(rest),
            "${s[8]}${s[7]}", clean(rest.trimStart(']', '>', '`', '\'')),
        )
    }

    /** Friendly name of an APRS symbol (table character then code). */
    fun symbolName(sym: String?): String? {
        if (sym == null || sym.length < 2) return null
        val alt = sym[0] != '/'
        return when (sym[1]) {
            '>' -> S(R.string.car); 'k' -> S(R.string.truck); 'u' -> "Bus"; '<' -> S(R.string.motorbike); 'b' -> S(R.string.bicycle); '[' -> S(R.string.pedestrian); 'R' -> S(R.string.motorhome)
            '-' -> S(R.string.home_2); '_' -> S(R.string.weather_station); 'O' -> S(R.string.balloon); '^' -> S(R.string.aircraft); 'X' -> S(R.string.helicopter); 's', 'Y' -> S(R.string.boat)
            '#' -> S(R.string.digipeater); '&' -> S(R.string.internet_gateway); 'r' -> S(R.string.vhf_repeater); 'a' -> "Ambulance"; 'f' -> S(R.string.fire_brigade)
            'j' -> "Jeep"; 'v' -> S(R.string.van); 'y' -> S(R.string.house_with_antenna); '$' -> S(R.string.phone); 'W' -> S(R.string.weather_station_nws)
            else -> if (alt) null else null
        }
    }
}

class AprsStation(val key: String, val call: String) {
    var name: String? = null
    var lat: Double? = null
    var lon: Double? = null
    var speedKn: Double? = null
    var courseDeg: Int? = null
    var altitudeM: Double? = null
    var symbol: String? = null
    var comment: String? = null
    var status: String? = null
    var weather: AprsWeather? = null
    var via: String? = null
    var packets = 0
    var messages = 0
    var lastSeenMs = 0L
    var isObject = false
}

class AprsTracker {
    val stations = LinkedHashMap<String, AprsStation>()
    var total = 0
        private set

    fun update(r: AprsReport, nowMs: Long) {
        total++
        val key = if (r.name != null) "${r.kind.name}:${r.name}" else r.src
        if (r.killed) { stations.remove(key); return }
        val s = stations.getOrPut(key) { AprsStation(key, r.name ?: r.src) }
        s.packets++
        s.lastSeenMs = nowMs
        if (r.kind == AprsReport.Kind.Message) { s.messages++; return }
        if (r.name != null) { s.name = r.name; s.isObject = true }
        r.path.firstOrNull { it.endsWith("*") }?.let { s.via = it.trimEnd('*') } ?: run { if (r.path.isEmpty()) s.via = null }
        if (r.lat != null && r.lon != null) {
            s.lat = r.lat; s.lon = r.lon; s.speedKn = r.speedKn; s.courseDeg = r.courseDeg; s.altitudeM = r.altitudeM ?: s.altitudeM
        }
        r.symbol?.let { s.symbol = it }
        r.weather?.let { s.weather = it }
        if (r.kind == AprsReport.Kind.Status) s.status = r.comment else r.comment?.let { s.comment = it }
    }

    /** Beacons repeat every few minutes; stations not heard for an hour are forgotten. */
    fun prune(nowMs: Long) {
        stations.values.removeAll { nowMs - it.lastSeenMs > 60 * 60_000 }
    }
}

/**
 * Bell 202 AFSK (1200 Hz mark, 2200 Hz space, 1200 baud) from FM-discriminated baseband at 50 kS/s: tone correlators over
 * one bit, an amplitude-normalised decision, NRZI, and eight HDLC decoders on bit clocks one eighth of a bit apart.
 */
class AfskDemodulator(private val sampleRate: Double = 50_000.0, private val onFrame: (ByteArray) -> Unit) {
    private val len = (sampleRate / BAUD).roundToInt()
    private val spb = sampleRate / BAUD
    private val mre = FloatArray(len)
    private val mim = FloatArray(len)
    private val sre = FloatArray(len)
    private val sim = FloatArray(len)
    private var sMr = 0.0
    private var sMi = 0.0
    private var sSr = 0.0
    private var sSi = 0.0
    private var phM = 0.0
    private var phS = 0.0
    private val incM = 2 * PI * 1200 / sampleRate
    private val incS = 2 * PI * 2200 / sampleRate
    private var pr = 0f
    private var pi = 0f
    private var mean = 0f
    private var peakM = 1e-9
    private var peakS = 1e-9
    private var n = 0L
    private val next = DoubleArray(PHASES) { len + it * spb / PHASES }
    private val prev = IntArray(PHASES)
    private val hdlc = Array(PHASES) { Hdlc { bits, count -> frame(bits, count) } }
    private val recent = HashMap<Int, Long>()

    private fun frame(bits: ByteArray, count: Int) {
        if (count % 8 != 0) return
        val out = ByteArray(count / 8)
        for (i in 0 until count) if (bits[i].toInt() == 1) out[i / 8] = (out[i / 8].toInt() or (1 shl (i % 8))).toByte() // LSB first
        val h = out.contentHashCode()
        val last = recent[h]
        if (last != null && n - last < 25_000) return
        recent[h] = n
        if (recent.size > 64) recent.entries.removeAll { n - it.value > 250_000 }
        onFrame(out)
    }

    /** Complex baseband: the instantaneous frequency (phase difference of successive samples) is the AFSK signal. */
    fun feed(re: FloatArray, im: FloatArray, count: Int) {
        for (i in 0 until count) {
            val a = re[i] * pr + im[i] * pi
            val b = im[i] * pr - re[i] * pi
            pr = re[i]; pi = im[i]
            step(atan2(b, a))
        }
    }

    /** Audio from a receiver's speaker or headphone output: already the FM-demodulated AFSK, at this demodulator's sample rate. */
    fun feedAudio(x: FloatArray, count: Int) {
        for (i in 0 until count) step(x[i])
    }

    private fun step(f: Float) {
        mean += (f - mean) / 200f
        val x = f - mean
        val k = (n % len).toInt()
        val cm = (x * cos(phM)).toFloat(); val sm = (-x * sin(phM)).toFloat()
        val cs = (x * cos(phS)).toFloat(); val ss = (-x * sin(phS)).toFloat()
        sMr += cm - mre[k]; sMi += sm - mim[k]; mre[k] = cm; mim[k] = sm
        sSr += cs - sre[k]; sSi += ss - sim[k]; sre[k] = cs; sim[k] = ss
        phM += incM; if (phM > 2 * PI) phM -= 2 * PI
        phS += incS; if (phS > 2 * PI) phS -= 2 * PI
        val em = sMr * sMr + sMi * sMi
        val es = sSr * sSr + sSi * sSi
        peakM = maxOf(em, peakM * 0.9995)
        peakS = maxOf(es, peakS * 0.9995)
        val level = if (em / peakM - es / peakS > 0) 1 else 0
        for (p in 0 until PHASES) {
            if (n >= next[p]) {
                hdlc[p].bit(if (level == prev[p]) 1 else 0) // NRZI: no change is a 1
                prev[p] = level
                next[p] += spb
            }
        }
        n++
    }

    companion object {
        const val BAUD = 1200.0
        private const val PHASES = 8
    }
}

/** HackRF bytes at 2 MS/s tuned fs/4 below the channel in; APRS reports out. */
class AprsReceiver(private val onReport: (AprsReport) -> Unit) {
    private val d1 = Decimator(4, 0.07, taps = 48)
    private val d2 = FloatDecimator(10, 0.024)
    private val aRe = FloatArray(32768)
    private val aIm = FloatArray(32768)
    private val bRe = FloatArray(4096)
    private val bIm = FloatArray(4096)
    private val afsk = AfskDemodulator { bytes -> Ax25.parse(bytes)?.let(Aprs::decode)?.let(onReport) }

    fun feed(buf: ByteArray, len: Int) {
        val k = d1.process(buf, len, aRe, aIm, 0)
        val m = d2.process(aRe, aIm, k, bRe, bIm)
        afsk.feed(bRe, bIm, m)
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
    }
}
