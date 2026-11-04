package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One LoRa channel a mesh network may use: its centre, its bandwidth and the spreading factors listened for there. */
data class MeshChannel(val hz: Long, val bwHz: Double, val sfs: List<Int>)

/**
 * Where the HackRF is tuned for a band, and the channels heard from there.
 *
 * - Narrow (`wide` false): tuned [loHz]; the first stage shifts by fs/4 and halves the rate, so the band is seen at
 *   1 MS/s centred on [loHz] + 500 kHz, and the HackRF's DC spike falls on the edge, outside every channel.
 * - Wide: the full 2 MS/s centred on [loHz], for a band wider than 1 MHz; [loHz] is then chosen between channels.
 */
data class MeshBand(val loHz: Long, val wide: Boolean, val channels: List<MeshChannel>) {
    val centreHz get() = if (wide) loHz else loHz + MeshRadio.SAMPLE_RATE / 4
}

/** A frame with a valid header, from a channel of the band. */
class MeshFrame(val hz: Long, val bwHz: Double, val sf: Int, val frame: LoraFrame)

/**
 * HackRF bytes at 2 MS/s in, LoRa frames of a mesh network out (Meshtastic, MeshCore): each channel is mixed down,
 * filtered and brought to two samples per chip, then fed to one [LoraReceiver] per spreading factor. The channels are
 * independent and run on a small pool of threads; their frames reach [onFrame] on the caller's thread, in order, once each
 * block is done. Frames whose CRC fails are passed on too (counted, not shown).
 */
class MeshRadio(val band: MeshBand, private val onFrame: (MeshFrame) -> Unit) {
    private val rate = if (band.wide) 2_000_000.0 else 1_000_000.0
    private val threads = minOf(band.channels.size, maxOf(1, Runtime.getRuntime().availableProcessors() - 1), 4)
    private val pool: java.util.concurrent.ExecutorService? = if (threads < 2) null else
        java.util.concurrent.Executors.newFixedThreadPool(threads) { r -> Thread(r, "mesh-dsp").apply { isDaemon = true } }
    private val d1 = Decimator(2, 450_000.0 / 2_000_000.0, 32)
    private val re = FloatArray(131072 + 8)
    private val im = FloatArray(re.size)

    private inner class Channel(val ch: MeshChannel) {
        val frames = ArrayList<MeshFrame>()
        private val outRate = LoraReceiver.OS * ch.bwHz
        // From 2 MS/s, a short first stage to 1 MS/s: only what folds onto the channel must go.
        private val pre = if (band.wide) FloatDecimator(2, 200_000.0 / 2_000_000.0, 16) else null
        private val decim = (1_000_000.0 / outRate).toInt()
        // Steep, so a neighbouring transmission does not fold in: 0.56 of the bandwidth on either side.
        private val dec = FloatDecimator(decim, 0.56 * ch.bwHz / 1_000_000.0, (24 * decim).coerceIn(32, 160))
        private val step = -2 * PI * (ch.hz - band.centreHz) / rate
        private val sr = cos(step)
        private val sj = sin(step)
        private var pr = 1.0
        private var pi = 0.0
        private val mr = FloatArray(re.size)
        private val mi = FloatArray(re.size)
        private val hr = FloatArray(re.size)
        private val hi = FloatArray(re.size)
        private val or = FloatArray(re.size)
        private val oi = FloatArray(re.size)
        val rx = ch.sfs.map { sf -> LoraReceiver(sf, ch.bwHz, ch.hz.toDouble()) { f -> frames += MeshFrame(ch.hz, ch.bwHz, sf, f) } }

        fun run(k: Int) {
            var p = pr; var q = pi
            for (i in 0 until k) {
                mr[i] = (re[i] * p - im[i] * q).toFloat(); mi[i] = (re[i] * q + im[i] * p).toFloat()
                val n = p * sr - q * sj; q = p * sj + q * sr; p = n
            }
            val g = 1 / hypot(p, q); pr = p * g; pi = q * g
            val n = if (pre != null) dec.process(hr, hi, pre.process(mr, mi, k, hr, hi), or, oi) else dec.process(mr, mi, k, or, oi)
            for (r in rx) r.feed(or, oi, n)
        }
    }

    private val channels = band.channels.map { Channel(it) }
    val receivers: List<LoraReceiver> get() = channels.flatMap { it.rx }
    private val jobs: List<(Int) -> Unit> = channels.map { c -> { k: Int -> c.run(k) } }

    /** Stops the worker threads (they are daemons, so this only frees them sooner). */
    fun close() { pool?.shutdown() }

    fun feed(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val m = minOf(2 * 2 * 65536, len - off)
            val k = if (band.wide) {
                val n = m / 2
                for (i in 0 until n) { re[i] = buf[off + 2 * i].toFloat(); im[i] = buf[off + 2 * i + 1].toFloat() }
                n
            } else d1.process(buf.copyOfRange(off, off + m), m, re, im, 0)
            feedFloat(k)
            off += m
        }
    }

    private fun feedFloat(k: Int) {
        val p = pool
        if (p == null || p.isShutdown) jobs.forEach { it(k) }
        else p.invokeAll(jobs.map { j -> java.util.concurrent.Callable { j(k) } }).forEach { it.get() }
        for (c in channels) { c.frames.forEach(onFrame); c.frames.clear() }
    }

    /** Complex samples at the band's rate (1 MS/s narrow, 2 MS/s wide), centred on [MeshBand.centreHz] (tests). */
    fun feedComplex(r: FloatArray, i: FloatArray, n: Int) {
        var off = 0
        while (off < n) {
            val m = minOf(65536, n - off)
            System.arraycopy(r, off, re, 0, m); System.arraycopy(i, off, im, 0, m)
            feedFloat(m)
            off += m
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
    }
}
