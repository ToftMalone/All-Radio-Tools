package com.allnetworktools

import com.allnetworktools.data.sdr.Aprs
import com.allnetworktools.data.sdr.AprsReceiver
import com.allnetworktools.data.sdr.AprsReport
import com.allnetworktools.data.sdr.AprsTracker
import com.allnetworktools.data.sdr.Ax25
import com.allnetworktools.data.sdr.Ax25Frame
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsTest {
    private fun addr(call: String, last: Boolean, repeated: Boolean = false): ByteArray {
        val (c, ssid) = call.split("-").let { it[0] to (it.getOrNull(1)?.toInt() ?: 0) }
        val out = ByteArray(7)
        for (i in 0 until 6) out[i] = ((c.getOrElse(i) { ' ' }.code) shl 1).toByte()
        out[6] = (0x60 or (ssid shl 1) or (if (last) 1 else 0) or (if (repeated) 0x80 else 0)).toByte()
        return out
    }

    private fun ax25(src: String, dst: String, path: List<String>, info: String): ByteArray {
        val out = ArrayList<Byte>()
        out += addr(dst, false).toList()
        out += addr(src, path.isEmpty()).toList()
        path.forEachIndexed { i, p -> out += addr(p.trimEnd('*'), i == path.lastIndex, p.endsWith("*")).toList() }
        out += 0x03; out += 0xF0.toByte()
        out += info.toByteArray(Charsets.ISO_8859_1).toList()
        return out.toByteArray()
    }

    private fun decode(info: String, dst: String = "APRS", src: String = "F4ABC-9", path: List<String> = emptyList()): AprsReport? =
        Aprs.decode(Ax25.parse(ax25(src, dst, path, info))!!)

    @Test fun parsesAddressesAndPath() {
        val f = Ax25.parse(ax25("F4ABC-9", "APRS", listOf("WIDE1-1*", "WIDE2-1"), "!4903.50N/07201.75W>x"))!!
        assertEquals("F4ABC-9", f.src)
        assertEquals("APRS", f.dst)
        assertEquals(listOf("WIDE1-1*", "WIDE2-1"), f.path)
        assertEquals("!4903.50N/07201.75W>x", String(f.info))
    }

    @Test fun uncompressedPosition() {
        val r = decode("!4903.50N/07201.75W>Test 001234")!!
        assertEquals(49.0583, r.lat!!, 1e-4)
        assertEquals(-72.0292, r.lon!!, 1e-4)
        assertEquals("/>", r.symbol)
        assertEquals("Voiture", Aprs.symbolName(r.symbol))
        assertEquals("Test 001234", r.comment)
    }

    @Test fun positionWithCourseSpeedAndAltitude() {
        val r = decode("=4903.50N/07201.75W>088/036/A=001234 mobile")!!
        assertEquals(88, r.courseDeg)
        assertEquals(36.0, r.speedKn!!, 1e-9)
        assertEquals(376.2, r.altitudeM!!, 0.1)
    }

    @Test fun compressedPosition() {
        val r = decode("=/5L!!<*e7>7P[")!!
        assertEquals(49.5, r.lat!!, 1e-3)
        assertEquals(-72.75, r.lon!!, 1e-3)
        assertEquals("/>", r.symbol)
    }

    @Test fun weatherReports() {
        val a = decode("_10090556c220s004g005t077r000p000P000h50b09900")!!
        assertEquals(AprsReport.Kind.Weather, a.kind)
        assertEquals(220, a.weather!!.windDir)
        assertEquals(4, a.weather!!.windMph)
        assertEquals(5, a.weather!!.gustMph)
        assertEquals(77, a.weather!!.tempF)
        assertEquals(50, a.weather!!.humidity)
        assertEquals(990.0, a.weather!!.pressureHpa!!, 1e-9)
        val b = decode("!4903.50N/07201.75W_220/004g005t077r000p000P000h00b09900")!!
        assertEquals(AprsReport.Kind.Weather, b.kind)
        assertEquals(100, b.weather!!.humidity)
        assertEquals(49.0583, b.lat!!, 1e-4)
    }

    /** Mic-E encoder written from the specification, independent of the decoder. */
    private fun micE(lat: Double, lon: Double, speed: Int, course: Int): Pair<String, String> {
        val north = lat >= 0
        val west = lon < 0
        val la = Math.abs(lat)
        val deg = la.toInt()
        val minutes = Math.round((la - deg) * 6000).toInt()
        val digits = intArrayOf(deg / 10, deg % 10, minutes / 1000, (minutes / 100) % 10, (minutes / 10) % 10, minutes % 10)
        val lo = Math.abs(lon)
        val ld = lo.toInt()
        val lm = Math.round((lo - ld) * 6000).toInt()
        val offset = ld < 10 || ld >= 100
        val dest = StringBuilder()
        for (i in 0 until 6) {
            val high = when (i) { 3 -> north; 4 -> offset; 5 -> west; else -> false }
            dest.append(if (high) 'P' + digits[i] else '0' + digits[i])
        }
        val rawDeg = when { ld < 10 -> ld + 90; ld >= 100 -> ld - 100; else -> ld }
        val rawMin = if (lm / 100 < 10) lm / 100 + 60 else lm / 100
        val info = StringBuilder("`")
        info.append((rawDeg + 28).toChar()).append((rawMin + 28).toChar()).append((lm % 100 + 28).toChar())
        info.append((speed / 10 + 28).toChar()).append(((speed % 10) * 10 + course / 100 + 28).toChar()).append((course % 100 + 28).toChar())
        info.append('>').append('/').append("Mic-E test")
        return dest.toString() to info.toString()
    }

    @Test fun micE() {
        for ((la, lo) in listOf(48.8566 to 2.3522, 33.4273 to -112.129, -33.8688 to 151.2093, 61.2 to -149.9)) {
            val (dst, info) = micE(la, lo, 57, 251)
            val r = decode(info, dst = dst)!!
            assertEquals(AprsReport.Kind.MicE, r.kind)
            assertEquals(la, r.lat!!, 0.001)
            assertEquals(lo, r.lon!!, 0.001)
            assertEquals(57.0, r.speedKn!!, 1e-9)
            assertEquals(251, r.courseDeg)
            assertEquals("/>", r.symbol)
        }
        // The destination S32U6T of the specification's example is 33°25.64' N.
        val spec = decode("`(_fn\"Oj/", dst = "S32U6T")!!
        assertEquals(33.4273, spec.lat!!, 1e-4)
        assertEquals(20.0, spec.speedKn!!, 1e-9)
        assertEquals(251, spec.courseDeg)
    }

    @Test fun objectsStatusAndMessages() {
        val o = decode(";LEADER   *092345z4903.50N/07201.75W>088/036")!!
        assertEquals("LEADER", o.name)
        assertEquals(AprsReport.Kind.Object, o.kind)
        assertEquals(36.0, o.speedKn!!, 1e-9)
        val s = decode(">Net ce soir 20h")!!
        assertEquals("Net ce soir 20h", s.comment)
        // A message is recognised, but nothing of it is kept.
        val m = decode(":F4XYZ    :Bonjour, rendez-vous demain{001")!!
        assertEquals(AprsReport.Kind.Message, m.kind)
        assertNull(m.comment)
        assertNull(m.lat)
    }

    @Test fun trackerMergesBeaconsAndDropsKilledObjects() {
        val t = AprsTracker()
        t.update(decode("!4903.50N/07201.75W>088/036 en route")!!, 0)
        t.update(decode(">QRV")!!, 1000)
        t.update(decode(":F4XYZ    :salut{1")!!, 2000)
        val s = t.stations["F4ABC-9"]!!
        assertEquals(3, s.packets)
        assertEquals(1, s.messages)
        assertEquals("en route", s.comment)
        assertEquals("QRV", s.status)
        t.update(decode(";BALISE   *092345z4903.50N/07201.75W/")!!, 3000)
        assertNotNull(t.stations["Object:BALISE"])
        t.update(decode(";BALISE   _092345z4903.50N/07201.75W/")!!, 4000)
        assertNull(t.stations["Object:BALISE"])
        t.prune(61 * 60_000L)
        assertTrue(t.stations.isEmpty())
    }

    /** HDLC line bits of whole frames: flags, FCS, stuffing, NRZI. */
    private fun lineBits(frames: List<ByteArray>): IntArray {
        val out = ArrayList<Int>()
        repeat(40) { for (b in intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)) out += b }
        for (f in frames) {
            val data = ArrayList<Int>()
            for (byte in f) for (k in 0 until 8) data += (byte.toInt() shr k) and 1
            var crc = 0xFFFF
            for (x in data) crc = if ((crc xor x) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            crc = crc xor 0xFFFF
            for (k in 0 until 16) data += (crc shr k) and 1
            var ones = 0
            for (x in data) { out += x; if (x == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0 }
            repeat(12) { for (b in intArrayOf(0, 1, 1, 1, 1, 1, 1, 0)) out += b }
        }
        var level = 1
        return IntArray(out.size) { if (out[it] == 0) level = 1 - level; level }
    }

    private fun afsk(line: IntArray, noise: Double, seed: Long, baud: Double = 1200.0): ByteArray {
        val rnd = Random(seed)
        val fs = 2_000_000.0
        val spb = fs / baud
        val n = ((line.size + 20) * spb).toInt()
        var psi = 0.0
        var pa = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val bit = (i / spb).toInt()
            val tone = if (bit < line.size && line[bit] == 0) 2200.0 else 1200.0
            pa += 2 * PI * tone / fs
            psi += 2 * PI * (fs / 4 + 1500 + 3000 * sin(pa)) / fs // 1.5 kHz of tuning error, 3 kHz deviation
            iq[2 * i] = (cos(psi) * 40 + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (sin(psi) * 40 + rnd.nextGaussian() * noise).toInt().coerceIn(-127, 127).toByte()
        }
        return iq
    }

    @Test fun demodulatesPacketsFromHackRfSamples() {
        val (dst, mic) = micE(48.8566, 2.3522, 57, 251)
        val frames = listOf(
            ax25("F4ABC-9", "APRS", listOf("WIDE1-1"), "!4903.50N/07201.75W>Test 001234"),
            ax25("F5XYZ", dst, listOf("WIDE2-1"), mic),
        )
        val got = ArrayList<AprsReport>()
        val rx = AprsReceiver { got += it }
        val iq = afsk(lineBits(frames), noise = 8.0, seed = 6)
        var i = 0
        while (i < iq.size) { val len = minOf(131072, iq.size - i); rx.feed(iq.copyOfRange(i, i + len), len); i += len }
        assertEquals(listOf("F4ABC-9", "F5XYZ"), got.map { it.src })
        assertEquals(49.0583, got[0].lat!!, 1e-4)
        assertEquals(48.8566, got[1].lat!!, 0.001)
    }

    /** What failed: a long beacon, and a tracker whose 1200 baud is 1.5% slow. */
    @Test fun longPacketFromASlowTracker() {
        val comment = "Telemetry 0123456789 and a long station description that goes on ".repeat(3).trim()
        val frame = ax25("F4ABC-9", "APRS", listOf("WIDE1-1", "WIDE2-1"), "!4903.50N/07201.75W>$comment")
        for (baud in listOf(1200.0, 1182.0, 1218.0)) {
            val got = ArrayList<AprsReport>()
            val rx = AprsReceiver { got += it }
            val iq = afsk(lineBits(listOf(frame)), noise = 15.0, seed = 3, baud = baud)
            var i = 0
            while (i < iq.size) { val len = minOf(131072, iq.size - i); rx.feed(iq.copyOfRange(i, i + len), len); i += len }
            assertEquals("baud $baud", 1, got.size)
            assertEquals(comment, got[0].comment)
        }
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(4)
        val iq = ByteArray(2 * 2_000_000) { (rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte() }
        val got = ArrayList<AprsReport>()
        val rx = AprsReceiver { got += it }
        var i = 0
        while (i < iq.size) { val len = minOf(131072, iq.size - i); rx.feed(iq.copyOfRange(i, i + len), len); i += len }
        assertTrue(got.isEmpty())
        assertNotNull(Ax25Frame("A", "B", emptyList(), ByteArray(0)))
    }
}
