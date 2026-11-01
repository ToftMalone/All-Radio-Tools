package com.allnetworktools

import com.allnetworktools.data.sdr.Decimator
import com.allnetworktools.data.sdr.FloatDecimator
import com.allnetworktools.data.sdr.Rs41
import com.allnetworktools.data.sdr.Rs41Demodulator
import com.allnetworktools.data.sdr.SondeFrame
import com.allnetworktools.data.sdr.SondeTracker
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rs41Test {
    private fun geoToEcef(lat: Double, lon: Double, h: Double): DoubleArray {
        val a = 6378137.0
        val e2 = 6.69437999014e-3
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lon)
        val n = a / sqrt(1 - e2 * sin(p) * sin(p))
        return doubleArrayOf((n + h) * cos(p) * cos(l), (n + h) * cos(p) * sin(l), (n * (1 - e2) + h) * sin(p))
    }

    private fun putBlock(f: ByteArray, pos: Int, id: Int, data: ByteArray): Int {
        f[pos] = id.toByte(); f[pos + 1] = data.size.toByte()
        data.copyInto(f, pos + 2)
        val crc = Rs41.crc16(f, pos + 2, data.size)
        f[pos + 2 + data.size] = crc.toByte(); f[pos + 3 + data.size] = (crc shr 8).toByte()
        return pos + 4 + data.size
    }

    /** A descrambled standard frame: status block (frame number, serial, battery) and GPS position block. */
    private fun frame(nb: Int, lat: Double, lon: Double, alt: Double, climb: Double): ByteArray {
        val f = ByteArray(Rs41.STD_LEN)
        intArrayOf(0x86, 0x35, 0xf4, 0x40, 0x93, 0xdf, 0x1a, 0x60).forEachIndexed { i, b -> f[i] = b.toByte() }
        f[0x38] = 0x0F
        val status = ByteArray(40)
        status[0] = nb.toByte(); status[1] = (nb shr 8).toByte()
        "V3420117".toByteArray().copyInto(status, 2)
        status[10] = 29
        var p = putBlock(f, 0x39, 0x79, status)
        val x = geoToEcef(lat, lon, alt)
        // Velocity: straight up by [climb] m/s, i.e. along the local vertical.
        val up = doubleArrayOf(cos(Math.toRadians(lat)) * cos(Math.toRadians(lon)), cos(Math.toRadians(lat)) * sin(Math.toRadians(lon)), sin(Math.toRadians(lat)))
        val gps = ByteArray(21)
        fun put32(o: Int, v: Int) { for (k in 0 until 4) gps[o + k] = (v shr (8 * k)).toByte() }
        fun put16(o: Int, v: Int) { gps[o] = v.toByte(); gps[o + 1] = (v shr 8).toByte() }
        for (k in 0 until 3) { put32(4 * k, Math.round(x[k] * 100).toInt()); put16(12 + 2 * k, Math.round(up[k] * climb * 100).toInt()) }
        gps[18] = 9
        p = putBlock(f, p + 0x2C, 0x7B, gps) // leave room as real frames have PTU/GPS1 blocks in between
        return f
    }

    /** Fills in both Reed-Solomon parity blocks, as the sonde does (a short frame counts as zero padded to 518). */
    private fun seal(f: ByteArray): ByteArray {
        val buf = f.copyOf(Rs41.EXT_LEN)
        for (w in 0..1) {
            val par = Rs41.rsParity(IntArray(231) { buf[0x38 + 2 * it + w].toInt() and 0xFF })
            for (i in 0 until 24) f[0x08 + 24 * w + i] = par[i].toByte()
        }
        return f
    }

    @Test fun reedSolomonMendsUpToTwelveBytesPerCodeword() {
        val rnd = Random(11)
        repeat(50) { round ->
            val msg = IntArray(231) { rnd.nextInt(256) }
            val cw = Rs41.rsParity(msg) + msg
            val bad = cw.copyOf()
            val n = round % 13
            val where = (0 until 255).shuffled(rnd).take(n)
            for (i in where) bad[i] = bad[i] xor (1 + rnd.nextInt(255))
            assertEquals(n, Rs41.rsDecode(bad))
            assertTrue(cw.contentEquals(bad))
        }
        // Thirteen is one too many: refused, never "corrected" into another codeword here.
        val msg = IntArray(231) { it }
        val bad = Rs41.rsParity(msg) + msg
        for (i in 0 until 13) bad[10 * i] = bad[10 * i] xor 0x55
        assertEquals(-1, Rs41.rsDecode(bad))
    }

    @Test fun reedSolomonParityMatchesTheGenerator() {
        // x^24 mod g(x) is g's own low part: the generator rs1729/RS prints for (X-α^0)…(X-α^23), constant term first.
        val p = Rs41.rsParity(IntArray(231).also { it[0] = 1 })
        val g = intArrayOf(
            0x75, 0x90, 0xD9, 0x7F, 0xF7, 0xED, 0x01, 0xCE, 0x2B, 0x3D, 0x48, 0x82,
            0x49, 0xE5, 0x96, 0x73, 0x66, 0xD8, 0xED, 0xB2, 0x46, 0xA9, 0x76, 0x7A,
        )
        assertTrue(g.contentEquals(p))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Real frames, already descrambled, from rs1729/RS (rs41/rs41.txt and ecc/ecc.txt). */
    private val realShort = hex("8635f44093df1a602c87e0fa0521e8943d9cef4c7a67393f6d39fb546461f2111b6447ab79a746c80350cda5344157f8c0c12234f46902220f792816174b313933303239331a00000300000a00002f0007322ce53e31991abf12dada3eb68468c16755d51c7a2a15310216060245f302000d08a31607821e08bb210219060243f302000000000000000000000000000000220d7c1e0807d03cdc071fd81ddb19d70a8d0eb602b60cb518d40692ff00ff00ff001c277d59b8d83301ff0f881f0f38f4fe18b283038735ff000000003eb8ff4947201e6e3aff55415f13fc6e005440440cf100009e9f7406f85800832b631719d70010bebc172a8b00000000000000000000000000000000000000000000a48b7b15366181193ef05d07e1245b1be0f721f801f60804107b0b76110000000000000000000000000000000000ecc7")
    private val realLong = hex("8635f44093df1a608f9b1025bf8ec9e28ad68413c31788307e9881c5cb2f37f754fa09b711c5c39977ed8fbf22377b3e5e1cee59fc644b19f0792896134b343032303234341c00000100000c00007a0007320f00000000008920bac20000000000000092697a2ae9030226fd015de502363208522a075f330874040228fd015de502000000000000000000000000000000e7917c1e4d0750f1921703fb01f8068d1fd811f70bd604d50afa17f913d90c8b20f9a16a7d5921103501ff440000006c1f00cd977e059ab7009566fd191d1affd82fbf143fb8ff5277180991faff9ca1d10d441b01927bf211dd190190999f0553a1ff9120b10c3847ff06eeee0e571301a2c0891c000000cddd1a0882d10011167b153c154217941930005fc50b1eb9fde107d2050902115a537ea6ed343030313030303120313037393020202033312e37203036373520303334392030373030203132383636203630303520313339333120363031342031343038322035383830203738313420383032372031303039203930392039353631353632203935303839323220343238383339313633382032393335383636203539343238203335323439203636393920333738332034363837203637303120363930312037393939049a762d000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000f35a")
    private val realLongBad = hex("8635f44093df1a60421080e56396389c102cf6db9276308a11f9f8792cb620beae0662688bba0eb2c0bdfa3bd10f0368c07ad0ac33c77046f0792888134b343032303234341c00000100000d00007a000732013230323434254e00002c011205b43ca475537a2a95050229fd015be502d931084d2a075b3308ed060228fd015de50200000000000000000000000000000061d67c1e4d07a0ba92170e9003fa01f813b40c921ed811fa0bf504d20af920fa17f765137d59950b3501ffc43086200a1101420000000521008f527605b1b600faa7e10e3b1301acce8e1c78c5ff0de3c41479b6ff48781a09dcfdffa2f1c30d51190151e0e4113d1701e27da60504a4ffdb2511085bd1003f05bd0c8346ffda787b156d2f421735a22f00f88b0b1ea7fe220b23030902112a417ea5d0343030333030303120313036313320202033312e362030363733203033353220303730302031323235312033383030203133343036203535333920313430353820353239332037383030203830323120373334203931392039353533383738203937323839373820343238383338353332362032393032313631203539313039203335303539203637303020333738352034363838203636393920363839392038303030c8e4762e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000bf08")
    private val realLongGood = hex("8635f44093df1a60421080e56396389c102cf6db9276308a11f9f8792cb620beae0662688bba0eb2c0bdfa3bd10f0368c07ad0ac33c77046f0792888134b343032303234341c00000100000d00007a000732013230323434254e00002c011205b43ca475537a2a95050229fd015be502d931084d2a075b3308ed060228fd015de50200000000000000000000000000000061d67c1e4d07a0ba92170e9003fa01f813b40c921fd811fa0bf504d20af920fa17f765137d59950b3501ffc43086200a1101420000000521008f527605b1b600faa7e10e3b1301acce8e1c78c5ff0de3c41479b6ff48781a09dcfdffa2f1c30d51190150e0e4113d1701e27da60504a4ffdb2511085bd1003f05bd0c8346ffda787b156d2f421735a22f00f88b0b1ea7fe220b23030902112a417ea5d0343030313030303120313036313320202033312e362030363733203033353220303730302031323235312033383030203133343036203535333920313430353820353239332037383030203830323120373334203931392039353533383738203937323839373820343238383338353332362032393032313631203539313039203335303539203637303020333738352034363838203636393920363839392038303030c8e4762e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000bf08")

    @Test fun realFramesFromTheReference() {
        // A clean short frame: both codewords check, and its blocks decode.
        assertEquals(0, Rs41.correct(realShort)!!.second)
        val r = Rs41.parse(realShort)!!
        assertEquals("K1930293", r.serial)
        assertEquals(0x1716, r.frameNumber)
        assertEquals(2.6, r.batteryV!!, 1e-9)
        assertEquals(46.05026, r.lat!!, 1e-5)
        assertEquals(16.11077, r.lon!!, 1e-5)
        assertEquals(28_410.0, r.altitudeM!!, 1.0)
        assertEquals(8, r.satellites)
        // A long frame with two bad parity bytes, which the reference mends ("2 Fehler").
        val (f2, n2) = Rs41.correct(realLong)!!
        assertEquals(2, n2)
        assertEquals(0x49, f2[0x08 + 26].toInt() and 0xFF)
        assertEquals("K4020244", Rs41.parse(realLong)!!.serial)
        // A long frame with three bad data bytes: one in the first codeword, two in the second, as the reference finds.
        val (f3, n3) = Rs41.correct(realLongBad)!!
        assertEquals(3, n3)
        assertTrue(realLongGood.contentEquals(f3))
    }

    @Test fun reedSolomonMendsAFrame() {
        val f = seal(frame(777, 48.7745, 2.0097, 12_000.0, 5.2))
        val hit = f.copyOf()
        // Twenty bad bytes spread over the frame, including the serial number and the GPS block.
        for (i in listOf(0x3D, 0x3E, 0x40, 0x45, 0x50, 0x90, 0xA1, 0xD0, 0x10A, 0x114, 0x115, 0x116, 0x11C, 0x121, 0x130, 0x0A, 0x21, 0x2F, 0x60, 0x101))
            hit[i] = (hit[i].toInt() xor 0xA5).toByte()
        val (fixed, n) = Rs41.correct(hit)!!
        assertEquals(20, n)
        assertTrue(f.contentEquals(fixed))
        val r = Rs41.parse(hit)!!
        assertEquals("V3420117", r.serial)
        assertEquals(777, r.frameNumber)
        assertEquals(48.7745, r.lat!!, 1e-5)
        // The length byte read wrong in two bits still means a short frame.
        val g = f.copyOf().also { it[0x38] = 0x2D }
        assertEquals(Rs41.STD_LEN, Rs41.length(g))
    }

    @Test fun headerBitsMatchScrambledHeader() {
        val bits = StringBuilder()
        intArrayOf(0x86, 0x35, 0xf4, 0x40, 0x93, 0xdf, 0x1a, 0x60).forEachIndexed { i, b ->
            val raw = b xor Rs41.MASK[i]
            for (k in 0 until 8) bits.append((raw shr k) and 1)
        }
        assertEquals(Rs41.HEADER_BITS, bits.toString())
    }

    @Test fun ecefConversion() {
        val x = geoToEcef(48.7745, 2.0097, 12_000.0)
        val (lat, lon, h) = Rs41.ecefToGeo(x[0], x[1], x[2])
        assertEquals(48.7745, lat, 1e-6)
        assertEquals(2.0097, lon, 1e-6)
        assertEquals(12_000.0, h, 0.01)
    }

    @Test fun parsesBlocksAndRejectsBadCrc() {
        val f = frame(4321, 48.7745, 2.0097, 12_000.0, 5.2)
        val r = Rs41.parse(f)!!
        assertEquals("V3420117", r.serial)
        assertEquals(4321, r.frameNumber)
        assertEquals(2.9, r.batteryV!!, 1e-9)
        assertEquals(48.7745, r.lat!!, 1e-5)
        assertEquals(12_000.0, r.altitudeM!!, 0.5)
        assertEquals(5.2, r.climbMs!!, 0.05)
        assertEquals(0.0, r.speedMs!!, 0.05)
        assertEquals(9, r.satellites)
        f[0x39 + 5] = (f[0x39 + 5] + 1).toByte() // corrupt the status block
        val r2 = Rs41.parse(f)!!
        assertNull(r2.serial)
        assertNotNull(r2.lat)
    }

    @Test fun demodulatesFromHackRfSamples() {
        val frames = (0 until 3).map { frame(100 + it, 48.80 + it * 0.001, 2.10, 15_000.0 + it * 5, 5.0) }
        val bits = ArrayList<Int>()
        repeat(400) { bits += it % 2 } // carrier and preamble-like filler before the first frame
        frames.forEach { f ->
            for (i in f.indices) {
                val raw = (f[i].toInt() and 0xFF) xor Rs41.MASK[i % 64]
                for (k in 0 until 8) bits += (raw shr k) and 1
            }
            repeat(37) { bits += 1 }
        }
        repeat(800) { bits += it % 2 }
        val fs = 2_000_000.0
        val sps = fs / Rs41.BAUD
        val rnd = Random(3)
        val n = (bits.size * sps).toInt()
        // FSK ±2.4 kHz around +500 kHz − 1.3 kHz of tuning error, 8-bit, noisy.
        var ph = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val b = bits[(i / sps).toInt().coerceAtMost(bits.size - 1)]
            ph += 2 * PI * (500_000.0 - 1300 + (if (b == 1) 2400.0 else -2400.0)) / fs
            iq[2 * i] = (cos(ph) * 40 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (sin(ph) * 40 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
        }
        val got = ArrayList<SondeFrame>()
        val demod = Rs41Demodulator(50_000.0) { f -> Rs41.parse(f)?.let { got += it } }
        val d1 = Decimator(4, 0.07, taps = 48)
        val d2 = FloatDecimator(10, 0.024)
        val aRe = FloatArray(32768); val aIm = FloatArray(32768)
        val bRe = FloatArray(4096); val bIm = FloatArray(4096)
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            val k = d1.process(iq.copyOfRange(i, i + len), len, aRe, aIm, 0)
            val m = d2.process(aRe, aIm, k, bRe, bIm)
            demod.feed(bRe, bIm, m)
            i += len
        }
        assertEquals(listOf(100, 101, 102), got.map { it.frameNumber })
        assertTrue(got.all { it.serial == "V3420117" })
        assertEquals(48.802, got[2].lat!!, 1e-5)
        assertEquals(15_010.0, got[2].altitudeM!!, 0.5)
    }

    @Test fun weakSignalIsMendedByReedSolomon() {
        // Ten copies of the reference's real frame, weak enough that most arrive with a few bad bytes.
        val bits = ArrayList<Int>()
        repeat(400) { bits += it % 2 }
        repeat(10) {
            for (i in realShort.indices) { val raw = (realShort[i].toInt() and 0xFF) xor Rs41.MASK[i % 64]; for (k in 0 until 8) bits += (raw shr k) and 1 }
            repeat(37) { bits += 1 }
        }
        repeat(800) { bits += it % 2 }
        val fs = 2_000_000.0
        val sps = fs / Rs41.BAUD
        val rnd = Random(3)
        val n = (bits.size * sps).toInt()
        var ph = 0.0
        val iq = ByteArray(2 * n)
        for (i in 0 until n) {
            val b = bits[(i / sps).toInt().coerceAtMost(bits.size - 1)]
            ph += 2 * PI * (500_000.0 - 1300 + (if (b == 1) 2400.0 else -2400.0)) / fs
            iq[2 * i] = (cos(ph) * 9 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = (sin(ph) * 9 + rnd.nextGaussian() * 25).toInt().coerceIn(-127, 127).toByte()
        }
        var errorFree = 0
        val got = ArrayList<SondeFrame>()
        val demod = Rs41Demodulator(50_000.0) { f -> if (f.contentEquals(realShort)) errorFree++; Rs41.parse(f)?.let { got += it } }
        val d1 = Decimator(4, 0.07, taps = 48)
        val d2 = FloatDecimator(10, 0.024)
        val aRe = FloatArray(32768); val aIm = FloatArray(32768)
        val bRe = FloatArray(4096); val bIm = FloatArray(4096)
        var i = 0
        while (i < iq.size) {
            val len = minOf(131072, iq.size - i)
            val k = d1.process(iq.copyOfRange(i, i + len), len, aRe, aIm, 0)
            demod.feed(bRe, bIm, d2.process(aRe, aIm, k, bRe, bIm))
            i += len
        }
        assertTrue("only $errorFree of 10 arrive intact", errorFree <= 5)
        assertEquals(10, got.count { it.serial == "K1930293" && it.lat != null })
    }

    @Test fun noiseAloneDecodesNothing() {
        val rnd = Random(5)
        val re = FloatArray(200_000) { rnd.nextGaussian().toFloat() }
        val im = FloatArray(200_000) { rnd.nextGaussian().toFloat() }
        val got = ArrayList<SondeFrame>()
        Rs41Demodulator(50_000.0) { f -> Rs41.parse(f)?.let { got += it } }.feed(re, im, re.size)
        assertTrue(got.isEmpty())
    }

    @Test fun trackerGroupsBySerialAndKeepsTheTrack() {
        val t = SondeTracker()
        fun fr(serial: String?, alt: Double) = SondeFrame("RS41", serial, 1, 2.9, 48.8, 2.1, alt, 3.0, 90.0, 5.0, 8)
        t.update(fr("S1234567", 1000.0), 0)
        t.update(fr(null, 1005.0), 1000) // status block lost: same sonde as before
        t.update(fr("S1234567", 1020.0), 2000)
        t.update(fr("T7654321", 500.0), 3000)
        assertEquals(listOf("S1234567", "T7654321"), t.sondes.keys.toList())
        val s = t.sondes["S1234567"]!!
        assertEquals(3, s.frames)
        assertEquals(1020.0, s.maxAltitudeM!!, 1e-9)
        assertEquals(3, s.track.size)
        t.prune(30 * 60_000L + 2500)
        assertEquals(listOf("T7654321"), t.sondes.keys.toList())
    }
}
