package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The LoRa transmit bit layer, block by block after gr-lora_sdr's TX (whitening, header, add_crc, hamming_enc,
 * interleaver, gray_demap): payload bytes to chirp symbol values. The receiver's [LoraPhy] undoes each step.
 */
object LoraEncoder {
    fun symbols(payload: ByteArray, sf: Int, cr: Int, hasCrc: Boolean = true, ldro: Boolean = false): IntArray {
        val nib = ArrayList<Int>()
        // Explicit header: length, coding rate and CRC flag, then its 5-bit checksum.
        val len = payload.size
        val h0 = len shr 4
        val h1 = len and 0x0F
        val h2 = (cr shl 1) or (if (hasCrc) 1 else 0)
        fun b(x: Int, i: Int) = (x shr i) and 1
        val c4 = b(h0, 3) xor b(h0, 2) xor b(h0, 1) xor b(h0, 0)
        val c3 = b(h0, 3) xor b(h1, 3) xor b(h1, 2) xor b(h1, 1) xor b(h2, 0)
        val c2 = b(h0, 2) xor b(h1, 3) xor b(h1, 0) xor b(h2, 3) xor b(h2, 1)
        val c1 = b(h0, 1) xor b(h1, 2) xor b(h1, 0) xor b(h2, 2) xor b(h2, 1) xor b(h2, 0)
        val c0 = b(h0, 0) xor b(h1, 1) xor b(h2, 3) xor b(h2, 2) xor b(h2, 1) xor b(h2, 0)
        nib += listOf(h0, h1, h2, c4, (c3 shl 3) or (c2 shl 2) or (c1 shl 1) or c0)
        // Whitening, low nibble first.
        payload.forEachIndexed { i, x ->
            val w = (x.toInt() and 0xFF) xor LoraPhy.whitening[i]
            nib += w and 0x0F; nib += w shr 4
        }
        if (hasCrc) {
            val crc = LoraPhy.payloadCrc(payload)
            nib += listOf(crc and 0xF, (crc shr 4) and 0xF, (crc shr 8) and 0xF, (crc shr 12) and 0xF)
        }
        // Hamming: the first SF-2 nibbles always at 4/8.
        val cws = nib.mapIndexed { i, x ->
            val crApp = if (i < sf - 2) 4 else cr
            val d = IntArray(4) { (x shr (3 - it)) and 1 }
            if (crApp != 1) {
                val p0 = d[3] xor d[2] xor d[1]
                val p1 = d[2] xor d[1] xor d[0]
                val p2 = d[3] xor d[2] xor d[0]
                val p3 = d[3] xor d[1] xor d[0]
                ((d[3] shl 7) or (d[2] shl 6) or (d[1] shl 5) or (d[0] shl 4) or (p0 shl 3) or (p1 shl 2) or (p2 shl 1) or p3) shr (4 - crApp)
            } else {
                val p4 = d[0] xor d[1] xor d[2] xor d[3]
                (d[3] shl 4) or (d[2] shl 3) or (d[1] shl 2) or (d[0] shl 1) or p4
            }
        }
        // Diagonal interleaver.
        val out = ArrayList<Int>()
        var cnt = 0
        while (cnt < cws.size) {
            val first = cnt < sf - 2
            val cwLen = 4 + if (first) 4 else cr
            val sfApp = if (first || ldro) sf - 2 else sf
            val cwBin = Array(sfApp) { i -> val v = cws.getOrElse(cnt + i) { 0 }; IntArray(cwLen) { (v shr (cwLen - 1 - it)) and 1 } }
            cnt += sfApp
            for (i in 0 until cwLen) {
                val inter = IntArray(sf)
                for (j in 0 until sfApp) inter[j] = cwBin[Math.floorMod(i - j - 1, sfApp)][i]
                if (first || ldro) inter[sfApp] = inter.sum() % 2
                out += inter.fold(0) { a, x -> (a shl 1) + x }
            }
        }
        // Inverse Gray, then +1.
        val nMod = 1 shl sf
        return IntArray(out.size) { i ->
            var g = out[i]
            for (j in 1 until sf) g = g xor (out[i] shr j)
            Math.floorMod(g + 1, nMod)
        }
    }
}

/**
 * A LoRa frame as complex baseband at [rate], computed as it is sent: [preamble] upchirps, the two sync-word chirps,
 * 2.25 downchirps, then the payload [symbols] (gr-lora_sdr's build_upchirp). [rate] must be a whole multiple of [bw].
 */
class LoraModulator(
    private val symbols: IntArray, private val sf: Int, private val bw: Double, override val rate: Int,
    syncWord: Int = 0x2B, private val preamble: Int = 16,
) : BasebandSource {
    private val n = 1 shl sf
    private val os = (rate / bw).toInt()
    private val ids: IntArray = IntArray(preamble + 2).also {
        it[preamble] = ((syncWord shr 4) and 0xF) shl 3
        it[preamble + 1] = (syncWord and 0xF) shl 3
    }
    private val payloadStart = (preamble + 4.25) * n
    private val frameChips = payloadStart + symbols.size * n
    private var sample = 0L

    override fun next(re: FloatArray, im: FloatArray): Int {
        var k = 0
        while (k < re.size) {
            val chip = sample.toDouble() / os
            if (chip >= frameChips) break
            val id: Int
            val down: Boolean
            val local: Double
            if (chip < payloadStart) {
                val s = (chip / n).toInt()
                if (chip >= (preamble + 2) * n) { id = 0; down = true } else { id = ids[s]; down = false }
                local = chip - s * n
            } else {
                val s = ((chip - payloadStart) / n).toInt()
                id = symbols[s]; down = false
                local = chip - payloadStart - s * n
            }
            val f0 = if (local < n - id) id.toDouble() / n - 0.5 else id.toDouble() / n - 1.5
            var ph = 2 * PI * (local * local / (2.0 * n) + f0 * local)
            if (down) ph = -ph
            re[k] = cos(ph).toFloat(); im[k] = sin(ph).toFloat()
            sample++
            k++
        }
        return if (k == 0) -1 else k
    }

    /** How long the frame lasts on air, in seconds. */
    val airtimeS: Double get() = frameChips / bw
}
