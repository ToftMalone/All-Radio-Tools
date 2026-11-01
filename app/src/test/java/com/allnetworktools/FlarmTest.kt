package com.allnetworktools

import com.allnetworktools.data.sdr.Flarm
import com.allnetworktools.data.sdr.FlarmFrame
import com.allnetworktools.data.sdr.FlarmReceiver
import com.allnetworktools.data.sdr.FlarmTracker
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packets built the way SoftRF sends them, then sent as 2-FSK through the whole receiver. */
class FlarmTest {
    private val now = 1_790_000_020_000L
    private val ts = (now / 1000).toInt()
    private val home = 45.21 to 5.84

    private fun packet(addr: Int, lat: Double, lon: Double, noTrack: Boolean = false): Flarm.V6 {
        val (la, lo) = Flarm.packPosition(lat, lon)
        return Flarm.withVelocity(Flarm.V6(addr, lat19 = la, lon20 = lo, alt = 1850, aircraftType = 1, noTrack = noTrack), 30.0, 135.0, 2.4)
    }

    @Test
    fun v6RoundTrip() {
        val p = packet(0xDD4F21, 45.3012, 5.9123)
        val b = Flarm.encodeV6(p, ts)
        assertEquals(0, Flarm.packetType(b))
        assertEquals(0xDD4F21, Flarm.packetAddr(b))
        val d = Flarm.decodeV6(b, ts)
        assertNotNull(d)
        d!!
        assertEquals(1850, d.alt)
        assertEquals(1, d.aircraftType)
        val (lat, lon) = Flarm.unpackPosition(d.lat19, d.lon20, home.first, home.second)
        assertEquals(45.3012, lat, 2e-5)
        assertEquals(5.9123, lon, 2e-5)
        val (speed, track, climb) = Flarm.velocity(d)
        assertEquals(30.0, speed, 0.6)
        assertEquals(135.0, track, 2.0)
        assertEquals(2.4, climb, 0.11)
        // Another 64 s step gives another key: the parity then fails, or the fields are garbage.
        val wrong = Flarm.decodeV6(b, ts + 64)
        assertTrue(wrong == null || wrong.alt != 1850 || wrong.lat19 != d.lat19)
    }

    /** CRC-16/CCITT-FALSE (check value 0x29B1), run over the sync's last three bytes then the payload, high byte first. */
    @Test
    fun frameCrcCoversTheSyncAddress() {
        assertEquals(0x29B1, Flarm.crc16("123456789".map { it.code }.toIntArray(), 9))
        val f = Flarm.encodeV6(packet(0xDD4F21, 45.3012, 5.9123), ts).copyOf(Flarm.FRAME)
        val c = Flarm.crc16(intArrayOf(0x31, 0xFA, 0xB6) + f.copyOf(Flarm.PAYLOAD), 27)
        assertEquals(c, Flarm.frameCrc(f))
        f[24] = c ushr 8; f[25] = c and 0xFF
        assertTrue(Flarm.checkCrc(f))
        // The payload's CRC alone, or the bytes swapped, is not a FLARM frame.
        val alone = Flarm.crc16(f, Flarm.PAYLOAD)
        assertTrue(!Flarm.checkCrc(f.copyOf().also { it[24] = alone ushr 8; it[25] = alone and 0xFF }))
        assertTrue(!Flarm.checkCrc(f.copyOf().also { it[24] = c and 0xFF; it[25] = c ushr 8 }))
    }

    @Test
    fun xxteaInverts() {
        val k = Flarm.key(ts, 0x123456)
        val v = intArrayOf(1, 2, 3, 0x7fffffff, -5)
        val c = v.copyOf()
        Flarm.btea(c, 5, k)
        assertTrue(!c.contentEquals(v))
        Flarm.btea(c, -5, k)
        assertTrue(c.contentEquals(v))
    }

    /** HackRF bytes at 2 MS/s tuned 500 kHz below 868.3 MHz: each packet on its channel, ±50 kHz, in noise. */
    private fun air(packets: List<Pair<IntArray, Int>>, seed: Int = 3): ByteArray {
        val rate = 2_000_000.0
        val spc = 20
        val gap = 150_000
        val rnd = Random(seed)
        val segs = packets.map { (payload, ch) -> Flarm.chips(payload) to (if (ch == 0) 400_000.0 else 600_000.0) }
        val n = gap + segs.sumOf { it.first.size * spc + 40_000 }
        val out = ByteArray(2 * n)
        var ph = 0.0
        var i = 0
        fun put(re: Double, im: Double) {
            out[2 * i] = (re * 60 + (rnd.nextDouble() - 0.5) * 16).roundToInt().coerceIn(-127, 127).toByte()
            out[2 * i + 1] = (im * 60 + (rnd.nextDouble() - 0.5) * 16).roundToInt().coerceIn(-127, 127).toByte()
            i++
        }
        repeat(gap) { put(0.0, 0.0) }
        for ((chips, off) in segs) {
            for (c in chips) repeat(spc) {
                ph += 2 * PI * (off + if (c == 1) 50_000.0 else -50_000.0) / rate
                put(cos(ph), sin(ph))
            }
            repeat(40_000) { put(0.0, 0.0) }
        }
        return out
    }

    private fun receive(bytes: ByteArray): List<FlarmFrame> {
        val frames = ArrayList<FlarmFrame>()
        val rx = FlarmReceiver { frames += it }
        var o = 0
        while (o < bytes.size) {
            val k = minOf(131072, bytes.size - o)
            rx.feed(bytes.copyOfRange(o, o + k), k)
            o += k
        }
        return frames
    }

    @Test
    fun receivesBothChannelsAndHonoursNoTrack() {
        val glider = packet(0xDD4F21, 45.3012, 5.9123)
        val glider2 = Flarm.withVelocity(glider.copy(alt = 1862), 31.0, 137.0, 2.0)
        val hidden = packet(0x3E1A07, 45.25, 5.80, noTrack = true)
        val list = listOf(
            Flarm.encodeV6(glider, ts) to 0,
            Flarm.encodeV6(hidden, ts) to 1,
            Flarm.encodeV6(glider2, ts) to 1,
            Flarm.encodeV6(hidden, ts) to 0,
        )
        val frames = receive(air(list))
        assertEquals(4, frames.size)
        assertEquals(listOf(0, 1, 1, 0), frames.map { it.channel })
        assertTrue(frames.all { it.snrDb > 10 })
        val t = FlarmTracker()
        frames.forEachIndexed { k, f -> t.update(f, now + k * 1000L) }
        assertEquals(setOf(0xDD4F21), t.aircraft.keys)
        val a = t.aircraft[0xDD4F21]!!
        assertEquals(1862, a.altM)
        assertEquals(2, a.packets)
        assertEquals(1, t.privateCount)
        val (lat, _) = Flarm.unpackPosition(a.lat19, a.lon20, home.first, home.second)
        assertTrue(abs(lat - 45.3012) < 1e-4)
    }

    @Test
    fun v7RoundTrip() {
        assertEquals(77, Flarm.lonDiv.size) // 14° to 89°, and one spare as in SoftRF
        for (v in listOf(0, 5, 255, 256, 300, 1000, 3000)) assertEquals(v.toDouble(), Flarm.descale(Flarm.enscale(v, 8, 2, false), 8, 2).toDouble(), v / 64.0 + 0.5)
        for (v in listOf(-24, -3, 0, 7, 64, 200)) assertEquals(v.toDouble(), Flarm.descale(Flarm.enscale(v, 6, 2, true), 6, 2).toDouble(), maxOf(1.0, abs(v) / 16.0))
        val b = Flarm.encodeV7(0xDD4F21, 45.3012, 5.9123, 1850, 30.0, 135.0, 2.4, ts)
        assertEquals(2, Flarm.packetType(b))
        val p = Flarm.decodeV7(b, ts)!!
        assertEquals(0xDD4F21, p.addr)
        assertEquals(1, p.aircraftType)
        assertEquals(ts and 15, p.tstamp)
        assertEquals(1850.0, p.alt.toDouble(), 2.0)
        assertEquals(30.0, p.speedMs, 0.2)
        assertEquals(2.4, p.climbMs, 0.1)
        assertEquals(135.0, p.courseDeg, 0.01)
        assertEquals(2, p.airborne)
        val (lat, lon) = Flarm.unpackV7(p.lat20, p.lon20, home.first, home.second)
        assertEquals(45.3012, lat, 1e-5)
        assertEquals(5.9123, lon, 1e-5)
        // Far north the longitude steps grow: still within a step of the truth.
        val n = Flarm.decodeV7(Flarm.encodeV7(0x123456, 64.1, -21.9, 300, 20.0, 10.0, 0.0, ts), ts)!!
        val (la2, lo2) = Flarm.unpackV7(n.lat20, n.lon20, 64.0, -22.0)
        assertEquals(64.1, la2, 1e-5); assertEquals(-21.9, lo2, Flarm.lonDivAt(64.1) * 1e-7)
        // Another 16 s step of the clock gives another mask: the fields no longer hold together.
        val wrong = (1..20).count { Flarm.decodeV7(b, ts + 16 * it)?.let { q -> q.aircraftType == 1 && abs(q.alt - 1850) < 50 } == true }
        assertTrue("$wrong", wrong <= 2)
    }

    @Test
    fun v7AircraftNeedTwoPacketsAndNoTrackIsHonoured() {
        val t = FlarmTracker()
        val f1 = Flarm.encodeV7(0xDD4F21, 45.3012, 5.9123, 1850, 30.0, 135.0, 2.4, ts).copyOf(Flarm.FRAME)
        val f2 = Flarm.encodeV7(0xDD4F21, 45.3020, 5.9130, 1856, 31.0, 136.0, 2.0, ts + 1).copyOf(Flarm.FRAME)
        t.update(FlarmFrame(f1, 0, 20.0), now)
        assertNull(t.aircraft[0xDD4F21])
        assertEquals(1, t.newProtocolDevices)
        t.update(FlarmFrame(f2, 1, 20.0), now + 1000)
        val a = t.aircraft[0xDD4F21]!!
        assertTrue(a.v7)
        assertEquals(1856.0, a.altM.toDouble(), 2.0)
        assertEquals(31.0 * 3.6, a.speedKmh, 1.0)
        assertEquals(0, t.newProtocolDevices)
        val (lat, lon) = Flarm.position(a, home.first, home.second)
        assertEquals(45.3020, lat, 1e-5); assertEquals(5.9130, lon, 1e-5)
        repeat(2) { t.update(FlarmFrame(Flarm.encodeV7(0x3E1A07, 45.25, 5.80, 900, 10.0, 90.0, 0.0, ts + it, noTrack = true).copyOf(Flarm.FRAME), 0, 20.0), now + it * 1000L) }
        assertNull(t.aircraft[0x3E1A07])
        assertEquals(1, t.privateCount)
    }

    @Test
    fun oneLonePacketIsNotAnAircraft() {
        val t = FlarmTracker()
        val b = Flarm.encodeV6(packet(0xABCDEF, 45.3, 5.9), ts)
        val f = b.copyOf(Flarm.FRAME)
        t.update(FlarmFrame(f, 0, 20.0), now)
        assertNull(t.aircraft[0xABCDEF])
        // A newer-protocol packet that does not decode is counted.
        val v7 = IntArray(Flarm.FRAME).also { it[3] = 0x02; it[0] = 0x11 }
        t.update(FlarmFrame(v7, 1, 20.0), now)
        assertEquals(1, t.newProtocolDevices)
    }
}
