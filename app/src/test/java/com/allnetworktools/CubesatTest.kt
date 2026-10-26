package com.allnetworktools

import com.allnetworktools.data.sdr.Ax25Frame
import com.allnetworktools.data.sdr.Cubesat
import com.allnetworktools.data.sdr.CubesatReceiver
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AX.25 telemetry from a satellite 8 kHz off by Doppler, at 1200 baud AFSK and 9600 baud G3RUH. */
class CubesatTest {
    private fun addr(call: String, ssid: Int, last: Boolean): ByteArray {
        val c = call.padEnd(6, ' ')
        return ByteArray(7) { if (it < 6) (c[it].code shl 1).toByte() else (0x60 or (ssid shl 1) or (if (last) 1 else 0)).toByte() }
    }

    private val frame = addr("CQ", 0, false) + addr("RS0ISS", 0, true) + byteArrayOf(0x03, 0xF0.toByte()) + ">ARISS - International Space Station".toByteArray()

    /** FM at 500 kS/s around 0 Hz + [doppler], from a baseband frequency for each sample. */
    private fun fm(dev: DoubleArray, doppler: Double): Pair<FloatArray, FloatArray> {
        val rnd = Random(2)
        val n = dev.size + 100_000
        val re = FloatArray(n); val im = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            ph += 2 * PI * (doppler + (if (i in 50_000 until 50_000 + dev.size) dev[i - 50_000] else 0.0)) / 500_000.0
            re[i] = (cos(ph) + 0.2 * rnd.nextGaussian()).toFloat(); im[i] = (sin(ph) + 0.2 * rnd.nextGaussian()).toFloat()
        }
        return re to im
    }

    private fun receive(mode: Cubesat.Mode, sig: Pair<FloatArray, FloatArray>): List<Ax25Frame> {
        val got = ArrayList<Ax25Frame>()
        val rx = CubesatReceiver(mode) { got += it }
        rx.dopplerHz = 8_000.0
        val (re, im) = sig
        var o = 0
        while (o < re.size) { val k = minOf(32768, re.size - o); rx.feedFloat(re.copyOfRange(o, o + k), im.copyOfRange(o, o + k), k); o += k }
        return got
    }

    @Test
    fun g3ruh9600() {
        // The scrambler and NRZI undo each other in the receiver's order.
        val bits = Cubesat.hdlcBits(listOf(frame))
        val air = Cubesat.nrzi(Cubesat.scramble(bits))
        val spb = 500_000.0 / 9600
        val n = (air.size * spb).toInt()
        val raw = DoubleArray(n) { if (air[(it / spb).toInt()] == 1) 3000.0 else -3000.0 }
        val w = (spb / 2).toInt()
        var acc = 0.0
        val dev = DoubleArray(n) { i -> acc += raw[i] - (if (i >= w) raw[i - w] else 0.0); acc / minOf(i + 1, w) }
        val got = receive(Cubesat.Mode.G3ruh9600, fm(dev, 8_000.0))
        assertEquals(1, got.size)
        assertEquals("RS0ISS", got[0].src)
        assertTrue(String(got[0].info).contains("Space Station"))
    }

    @Test
    fun afsk1200() {
        val levels = Cubesat.nrzi(Cubesat.hdlcBits(listOf(frame)))
        val spb = 500_000.0 / 1200
        val n = (levels.size * spb).toInt()
        var tone = 0.0
        val dev = DoubleArray(n) { i ->
            tone += 2 * PI * (if (levels[(i / spb).toInt()] == 1) 1200.0 else 2200.0) / 500_000.0
            3000.0 * sin(tone)
        }
        val got = receive(Cubesat.Mode.Afsk1200, fm(dev, 8_000.0))
        assertEquals(1, got.size)
        assertEquals("RS0ISS", got[0].src)
    }
}
