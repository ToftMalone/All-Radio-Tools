package com.allnetworktools

import com.allnetworktools.data.sdr.AnalogVideoDecoder
import com.allnetworktools.data.sdr.DroneBurst
import com.allnetworktools.data.sdr.DroneIdDetector
import com.allnetworktools.data.sdr.DroneWindow
import com.allnetworktools.data.sdr.Fft
import com.allnetworktools.data.sdr.FpvChannels
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class FpvTest {
    // ---- channel table -------------------------------------------------------------------------------------------

    @Test fun channelTableHasTheSixBands() {
        assertEquals(48, FpvChannels.all.size)
        assertEquals(5658, FpvChannels.byName("R1")!!.mhz)
        assertEquals(5917, FpvChannels.byName("R8")!!.mhz)
        assertEquals(5800, FpvChannels.byName("F4")!!.mhz)
        assertEquals(5865, FpvChannels.byName("A1")!!.mhz)
        assertEquals(5362, FpvChannels.byName("L1")!!.mhz)
        assertEquals(48, FpvChannels.all.map { it.name }.toSet().size)
        assertEquals(17, FpvChannels.ghz24.size)
        assertEquals(2360, FpvChannels.ghz24.first().mhz)
        assertEquals(2520, FpvChannels.ghz24.last().mhz)
        assertEquals(48, FpvChannels.scan(58).size)
        assertEquals(65, FpvChannels.scan(0).size)
    }

    // ---- analogue video ------------------------------------------------------------------------------------------

    /** PAL-like luminance at [fs]: field sync with equalising and broad pulses, then lines with a horizontal ramp. */
    private fun composite(fs: Double, fields: Int, lineUs: Double = 64.0, dark: IntRange = IntRange.EMPTY): FloatArray {
        val lineN = Math.round(lineUs * 1e-6 * fs).toInt()
        val half = lineN / 2
        fun us(v: Double) = Math.round(v * 1e-6 * fs).toInt()
        val out = ArrayList<Float>()
        fun pulseHalf(low: Int) { for (i in 0 until half) out += if (i < low) -0.3f else 0f }
        repeat(fields) {
            repeat(5) { pulseHalf(us(2.35)) }
            repeat(5) { pulseHalf(us(27.3)) }
            repeat(5) { pulseHalf(us(2.35)) }
            repeat(305) { line ->
                for (i in 0 until lineN) {
                    val t = i / fs * 1e6
                    out += when {
                        t < 4.7 -> -0.3f
                        // A patch darker than black, as wide as a broad pulse: what an FM link shows under a dark object.
                        line in dark && t in 20.0..45.0 -> -0.2f
                        t < 10.5 -> 0f
                        t < 62.5 -> 0.7f * ((t - 10.5) / 52.0).toFloat()
                        else -> 0f
                    }
                }
            }
        }
        return out.toFloatArray()
    }

    private fun fmModulate(video: FloatArray, fs: Double, polarity: Int, offsetHz: Double, noise: Double, seed: Long = 1): ByteArray {
        val rnd = Random(seed)
        val iq = ByteArray(video.size * 2)
        var ph = 0.0
        for (i in video.indices) {
            ph += 2 * PI * (offsetHz + polarity * 8e6 * video[i]) / fs
            val re = 60 * cos(ph) + rnd.nextGaussian() * noise
            val im = 60 * sin(ph) + rnd.nextGaussian() * noise
            iq[2 * i] = re.toInt().coerceIn(-127, 127).toByte()
            iq[2 * i + 1] = im.toInt().coerceIn(-127, 127).toByte()
        }
        return iq
    }

    private fun decode(iq: ByteArray, fs: Double): Triple<AnalogVideoDecoder, List<Triple<Int, Int, ByteArray>>, Int> {
        val lines = ArrayList<Triple<Int, Int, ByteArray>>()
        var fields = 0
        val d = AnalogVideoDecoder(fs, { f, l, p -> lines += Triple(f, l, p.copyOf()) }, { fields++ })
        var i = 0
        while (i < iq.size) { val n = minOf(131072, iq.size - i); d.feed(iq.copyOfRange(i, i + n), n); i += n }
        return Triple(d, lines, fields)
    }

    private fun correlation(line: ByteArray): Double {
        val n = line.size
        val x = DoubleArray(n) { (line[it].toInt() and 0xFF).toDouble() }
        val y = DoubleArray(n) { it.toDouble() }
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until n) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
        return sxy / Math.sqrt(sxx * syy + 1e-9)
    }

    @Test fun decodesPalVideoWhicheverWayTheFrequencyMoves() {
        val fs = 16e6
        val video = composite(fs, 10)
        for (pol in listOf(1, -1)) {
            val (d, lines, fields) = decode(fmModulate(video, fs, pol, offsetHz = 300e3, noise = 6.0), fs)
            assertEquals("pol $pol", pol, d.stats.polarity)
            assertEquals("PAL", d.stats.standard)
            assertTrue("quality ${d.stats.syncQuality}", d.stats.syncQuality > 0.9)
            assertTrue("fields $fields", fields >= 7)
            assertTrue("lines per field ${d.stats.linesPerField}", d.stats.linesPerField in 300..312)
            val picture = lines.filter { it.second in 100..200 }
            assertTrue(picture.size > 300)
            val good = picture.count { correlation(it.third) > 0.95 }
            assertTrue("ramp lines $good / ${picture.size}", good > picture.size * 0.9)
        }
    }

    /** Dark patches on consecutive lines look like broad pulses, but a line apart: they must not start a field. */
    @Test fun darkPatchesDoNotRollThePicture() {
        val fs = 16e6
        val (d, lines, fields) = decode(fmModulate(composite(fs, 10, dark = 120..160), fs, 1, 0.0, 5.0), fs)
        assertTrue("fields $fields", fields >= 8)
        assertEquals("field syncs", fields + 1, d.stats.vSyncs)
        assertTrue("lines per field ${d.stats.linesPerField}", d.stats.linesPerField in 300..312)
        // Every field numbers its lines from its own start: the dark band sits at the same lines in each.
        val darkLines = lines.filter { it.second in 100..200 && (it.third[150].toInt() and 0xFF) < 20 }.map { it.second }.toSet()
        assertTrue("dark lines $darkLines", darkLines.isNotEmpty() && darkLines.all { it in 110..170 })
    }

    @Test fun decodesAtHalfTheInputRateToo() {
        val fs = 8e6
        val video = composite(fs, 8)
        val iq = fmModulate(video.map { it * 0.45f }.toFloatArray(), fs, 1, 0.0, 4.0)
        val (d, _, fields) = decode(iq, fs)
        assertEquals("PAL", d.stats.standard)
        assertTrue(fields >= 5)
    }

    @Test fun tellsNtscFromPalByTheLineLength() {
        val fs = 16e6
        val (d, _, _) = decode(fmModulate(composite(fs, 8, lineUs = 63.556), fs, 1, 0.0, 5.0), fs)
        assertEquals("NTSC", d.stats.standard)
    }

    @Test fun noiseAndPlainCarriersAreNotVideo() {
        val fs = 16e6
        val rnd = Random(3)
        val noise = ByteArray(2 * 2_000_000) { (rnd.nextGaussian() * 30).toInt().coerceIn(-127, 127).toByte() }
        val (d1, lines1, _) = decode(noise, fs)
        assertTrue("quality ${d1.stats.syncQuality}", d1.stats.syncQuality < 0.3)
        assertTrue(lines1.size < 50)
        val carrier = ByteArray(2 * 2_000_000) { i -> (60 * (if (i % 2 == 0) cos(2 * PI * 1e6 * (i / 2) / fs) else sin(2 * PI * 1e6 * (i / 2) / fs))).toInt().toByte() }
        val (d2, _, _) = decode(carrier, fs)
        assertEquals(0.0, d2.stats.syncQuality, 0.01)
    }

    // ---- DJI DroneID presence ------------------------------------------------------------------------------------

    private val fs = 20e6

    /** [us] microseconds of OFDM-like signal [bwMhz] wide centred at [offsetMhz], over Gaussian noise of std [sigma] LSB. */
    private fun scene(totalMs: Double, bursts: List<Triple<Double, Double, Double>>, bwMhz: Double, offsetMhz: Double, sigma: Double, amp: Double, seed: Long = 5): ByteArray {
        val n = (totalMs * 1e-3 * fs).toInt()
        val rnd = Random(seed)
        val re = FloatArray(n) { (rnd.nextGaussian() * sigma).toFloat() }
        val im = FloatArray(n) { (rnd.nextGaussian() * sigma).toFloat() }
        val nfft = 2048
        val fft = Fft(nfft)
        val binMhz = fs / nfft / 1e6
        for ((startMs, durUs, _) in bursts) {
            val len = (durUs * 1e-6 * fs).toInt()
            val start = (startMs * 1e-3 * fs).toInt()
            var done = 0
            while (done < len) {
                val xr = FloatArray(nfft); val xi = FloatArray(nfft)
                for (k in 0 until nfft) {
                    val f = (if (k < nfft / 2) k else k - nfft) * binMhz
                    if (abs(f - offsetMhz) <= bwMhz / 2) { val ph = rnd.nextInt(4) * PI / 2 + PI / 4; xr[k] = cos(ph).toFloat(); xi[k] = -sin(ph).toFloat() } // conj for the inverse
                }
                fft.forward(xr, xi)
                val norm = (amp / Math.sqrt(bwMhz / binMhz)).toFloat()
                for (j in 0 until minOf(nfft, len - done)) {
                    re[start + done + j] += xr[j] * norm; im[start + done + j] += -xi[j] * norm
                }
                done += nfft
            }
        }
        return ByteArray(2 * n) { i -> (if (i % 2 == 0) re[i / 2] else im[i / 2]).toInt().coerceIn(-127, 127).toByte() }
    }

    private fun detect(iq: ByteArray, rate: Double = fs): List<DroneBurst> {
        val out = ArrayList<DroneBurst>()
        val d = DroneIdDetector(rate) { out += it }
        var i = 0
        while (i < iq.size) { val n = minOf(65536, iq.size - i); d.feed(iq.copyOfRange(i, i + n), n); i += n }
        return out
    }

    @Test fun findsDroneIdShapedBursts() {
        // 650 µs bursts, 9 MHz wide, at 3 MHz from the centre, 20 dB over the noise, every 600 ms is shortened to 4 ms here.
        val iq = scene(26.0, listOf(Triple(12.0, 650.0, 0.0), Triple(18.0, 650.0, 0.0)), 9.0, 3.0, sigma = 4.0, amp = 40.0)
        val found = detect(iq)
        assertEquals(2, found.size)
        for (b in found) {
            assertEquals(650.0, b.durationUs, 30.0)
            assertEquals(9.0, b.bandwidthMHz, 1.2)
            assertEquals(3.0, b.offsetMHz, 0.6)
        }
    }

    @Test fun ignoresWifiLikeAndOtherShapes() {
        val sigma = 4.0
        // 16 MHz wide: a Wi-Fi 20 MHz channel's occupied band.
        assertTrue(detect(scene(26.0, listOf(Triple(12.0, 650.0, 0.0)), 16.0, 0.0, sigma, 40.0)).isEmpty())
        // The right width but 2 ms long: a data stream, not a DroneID burst.
        assertTrue(detect(scene(26.0, listOf(Triple(12.0, 2000.0, 0.0)), 9.0, 0.0, sigma, 40.0)).isEmpty())
        // The right shape but 100 µs: a short packet.
        assertTrue(detect(scene(26.0, listOf(Triple(12.0, 100.0, 0.0)), 9.0, 0.0, sigma, 40.0)).isEmpty())
        // Narrow burst (a few MHz): Bluetooth or ZigBee-like.
        assertTrue(detect(scene(26.0, listOf(Triple(12.0, 650.0, 0.0)), 2.0, 0.0, sigma, 40.0)).isEmpty())
        // Only noise.
        assertTrue(detect(scene(26.0, emptyList(), 9.0, 0.0, sigma, 0.0)).isEmpty())
    }

    @Test fun periodicityNeedsTheBroadcastRhythm() {
        fun burst(t: Double) = DroneBurst(t, 650.0, 9.0, 0.0, 20.0, 0.5)
        val w = DroneWindow(2437.0)
        w.add(burst(0.1), 0, 0.0)
        assertTrue(!w.periodic)
        w.add(burst(0.9), 0, 0.0) // 800 ms later: not a multiple of 600 ms
        assertTrue(!w.periodic)
        w.add(burst(1.3), 0, 0.0) // 1.2 s after the first
        assertTrue(w.periodic)
    }

    /** Real DroneID captures (DJI Mini 2 and Mavic Air 2), converted to 20 MS/s signed bytes; skipped when not on disk. */
    @Test fun findsBurstsInRealCaptures() {
        val dir = System.getenv("DRONEID_SAMPLES")
        assumeTrue(dir != null)
        for ((name, expected) in listOf("mini2_sm_20ms_i8.bin" to 8, "mavic_air_2_20ms_i8.bin" to 1)) {
            val f = File(dir, name)
            assumeTrue(f.exists())
            val found = detect(f.readBytes())
            println("REAL $name: ${found.size} bursts " + found.joinToString { "%.0fus/%.1fMHz/%+.1f/%.0fdB/flat%.1f".format(it.durationUs, it.bandwidthMHz, it.offsetMHz, it.levelDb, it.flatnessDb) })
            assertTrue("$name: ${found.size}", found.size >= expected)
            assertNotNull(found.first())
        }
    }
}
