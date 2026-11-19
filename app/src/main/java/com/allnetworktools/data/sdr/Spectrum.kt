package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Averaged power spectrum of HackRF I/Q bytes: Blackman-Harris window, DC removed, FFT-shifted so bin 0 is the
 * lowest frequency, in dBFS (a full-scale tone reads 0 dB).
 */
class SpectrumAnalyzer(val size: Int) {
    private val fft = Fft(size)
    private val window = FloatArray(size) { i ->
        val x = 2 * PI * i / (size - 1)
        (0.35875 - 0.48829 * cos(x) + 0.14128 * cos(2 * x) - 0.01168 * cos(3 * x)).toFloat()
    }
    private val gain = window.sumOf { it.toDouble() }.let { it * it }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val acc = DoubleArray(size)
    var blocks = 0
        private set

    /** Adds up to [maxBlocks] FFTs taken across the buffer, half-overlapping when that many are asked for. */
    fun accumulate(buf: ByteArray, len: Int, maxBlocks: Int = 4) {
        val samples = len / 2
        if (samples < size) return
        val count = minOf(maxBlocks, 2 * samples / size - 1)
        val stride = if (count > 1) (samples - size) / (count - 1) else 0
        for (b in 0 until count) {
            val off = 2 * b * stride
            var mr = 0f
            var mi = 0f
            for (i in 0 until size) { mr += buf[off + 2 * i]; mi += buf[off + 2 * i + 1] }
            mr /= size; mi /= size
            for (i in 0 until size) {
                re[i] = (buf[off + 2 * i] - mr) / 128f * window[i]
                im[i] = (buf[off + 2 * i + 1] - mi) / 128f * window[i]
            }
            fft.forward(re, im)
            for (k in 0 until size) acc[k] += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            blocks++
        }
    }

    /** The averaged spectrum since the last call, in dBFS, lowest frequency first; then starts a new average. */
    fun frame(): FloatArray? {
        if (blocks == 0) return null
        val half = size / 2
        val out = FloatArray(size) { i ->
            val k = (i + half) % size
            (10 * log10((acc[k] / blocks / gain).coerceAtLeast(1e-14))).toFloat()
        }
        acc.fill(0.0)
        blocks = 0
        return out
    }
}
