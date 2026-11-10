package com.allnetworktools

import com.allnetworktools.data.sdr.ListenMode
import com.allnetworktools.data.sdr.ListenReceiver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic signals at 2 MS/s through the listening chain: the tone that goes in must be the tone that comes out. */
class ListenTest {
    private val fs = 2_000_000.0

    /** [amp] is the I/Q amplitude out of 127; [iq] gives the instantaneous phase and envelope for sample n. */
    private fun run(mode: ListenMode, offsetHz: Double, seconds: Double = 0.5, make: (Int) -> Pair<Double, Double>): FloatArray {
        val got = ArrayList<Float>()
        val rx = ListenReceiver { b, n -> for (i in 0 until n) got += b[i] }
        rx.mode = mode
        rx.offsetHz = offsetHz
        val n = (fs * seconds).toInt()
        val buf = ByteArray(2 * 65536)
        var i = 0
        while (i < n) {
            val c = minOf(65536, n - i)
            for (k in 0 until c) {
                val (phase, env) = make(i + k)
                buf[2 * k] = (env * 100 * cos(phase)).toInt().toByte()
                buf[2 * k + 1] = (env * 100 * sin(phase)).toInt().toByte()
            }
            rx.feed(buf, 2 * c)
            i += c
        }
        return got.toFloatArray()
    }

    /** Energy at [f] over the energy of the whole signal (Goertzel-style correlation), audio at 50 kS/s. */
    private fun share(a: FloatArray, f: Double, skip: Int = 5000): Double {
        var cr = 0.0; var ci = 0.0; var tot = 0.0
        for (k in skip until a.size) {
            val w = 2 * PI * f * k / 50_000
            cr += a[k] * cos(w); ci += a[k] * sin(w); tot += a[k] * a[k]
        }
        val n = (a.size - skip).toDouble()
        return 2 * (cr * cr + ci * ci) / (n * n) / (tot / n).coerceAtLeast(1e-12)
    }

    @Test fun amToneComesOut() {
        val off = 300_000.0
        val a = run(ListenMode.Am, off) { n -> 2 * PI * off * n / fs to 0.5 * (1 + 0.6 * sin(2 * PI * 1000 * n / fs)) }
        assertTrue("AM share ${share(a, 1000.0)}", share(a, 1000.0) > 0.8)
    }

    @Test fun narrowFmToneComesOut() {
        val off = -250_000.0
        var ph = 0.0
        val a = run(ListenMode.Nfm, off) { n ->
            ph += 2 * PI * (off + 3000 * sin(2 * PI * 1000 * n / fs)) / fs
            ph to 1.0
        }
        assertTrue("NFM share ${share(a, 1000.0)}", share(a, 1000.0) > 0.8)
    }

    @Test fun broadcastFmToneComesOut() {
        val off = 400_000.0
        var ph = 0.0
        val a = run(ListenMode.Wfm, off) { n ->
            ph += 2 * PI * (off + 50_000 * sin(2 * PI * 1000 * n / fs)) / fs
            ph to 1.0
        }
        assertTrue("WFM share ${share(a, 1000.0)}", share(a, 1000.0) > 0.8)
    }

    @Test fun upperSidebandKeepsItsPitchAndRejectsTheOtherSide() {
        val off = 200_000.0
        val usb = run(ListenMode.Usb, off) { n -> 2 * PI * (off + 1500) * n / fs to 1.0 }
        assertTrue("USB share ${share(usb, 1500.0)}", share(usb, 1500.0) > 0.8)
        // The same signal heard as lower sideband is nearly silent compared with the right one.
        val wrong = run(ListenMode.Lsb, off) { n -> 2 * PI * (off + 1500) * n / fs to 1.0 }
        val rightRms = usb.drop(20000).map { it * it }.average()
        val wrongRms = wrong.drop(20000).map { it * it }.average()
        assertTrue("rejection ${rightRms / wrongRms.coerceAtLeast(1e-12)}", rightRms > 100 * wrongRms)
    }

    @Test fun lowerSideband() {
        val off = -150_000.0
        val a = run(ListenMode.Lsb, off) { n -> 2 * PI * (off - 1500) * n / fs to 1.0 }
        assertTrue("LSB share ${share(a, 1500.0)}", share(a, 1500.0) > 0.8)
    }

    @Test fun squelchMutesWeakSignalsAndLetsStrongOnesThrough() {
        val off = 100_000.0
        fun peak(env: Double, squelch: Double?): Float {
            val got = ArrayList<Float>()
            val rx = ListenReceiver { b, n -> for (i in 0 until n) got += b[i] }
            rx.mode = ListenMode.Am; rx.offsetHz = off; rx.squelchDb = squelch
            val buf = ByteArray(2 * 65536)
            repeat(20) { blk ->
                for (k in 0 until 65536) {
                    val n = blk * 65536 + k
                    val e = env * (1 + 0.6 * sin(2 * PI * 1000 * n / fs))
                    buf[2 * k] = (e * 100 * cos(2 * PI * off * n / fs)).toInt().toByte()
                    buf[2 * k + 1] = (e * 100 * sin(2 * PI * off * n / fs)).toInt().toByte()
                }
                rx.feed(buf, buf.size)
            }
            return got.drop(10000).maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        }
        val open = peak(0.5, null)
        val shut = peak(0.5, -3.0)
        val low = peak(0.5, -40.0)
        assertTrue("open $open", open > 0.1f)
        assertTrue("shut $shut", shut == 0f)      // this signal sits around -8 dB: muted by a -3 dB squelch
        assertTrue("low $low", low > 0.1f)        // and let through by a -40 dB one
    }

    @Test fun levelFollowsTheSignal() {
        val off = 100_000.0
        val a = ListenReceiver { _, _ -> }
        a.mode = ListenMode.Am; a.offsetHz = off
        val buf = ByteArray(2 * 65536)
        repeat(6) { blk ->
            for (k in 0 until 65536) { val n = blk * 65536 + k; buf[2 * k] = (100 * cos(2 * PI * off * n / fs)).toInt().toByte(); buf[2 * k + 1] = (100 * sin(2 * PI * off * n / fs)).toInt().toByte() }
            a.feed(buf, buf.size)
        }
        // 100/128 of full scale is -2.1 dB.
        assertEquals(-2.1, a.levelDb, 1.0)
    }
}
