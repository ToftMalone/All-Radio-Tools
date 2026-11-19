package com.allnetworktools

import com.allnetworktools.data.sdr.LoraPhy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A LoRa transmitter for tests, written block by block after gr-lora_sdr's TX (whitening, header, add_crc,
 * hamming_enc, interleaver, gray_demap, modulate) so the receiver is checked against the reference, not against itself.
 */
object LoraTx {
    fun symbols(payload: ByteArray, sf: Int, cr: Int, hasCrc: Boolean = true, ldro: Boolean = false): IntArray {
        val nib = ArrayList<Int>()
        // header_impl
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
        // whitening_impl: low nibble first
        payload.forEachIndexed { i, x ->
            val w = (x.toInt() and 0xFF) xor LoraPhy.whitening[i]
            nib += w and 0x0F; nib += w shr 4
        }
        // add_crc_impl
        if (hasCrc) {
            val crc = LoraPhy.payloadCrc(payload)
            nib += listOf(crc and 0xF, (crc shr 4) and 0xF, (crc shr 8) and 0xF, (crc shr 12) and 0xF)
        }
        // hamming_enc_impl
        val cws = nib.mapIndexed { i, x ->
            val crApp = if (i < sf - 2) 4 else cr
            val d = IntArray(4) { (x shr (3 - it)) and 1 } // int2bool: MSB first
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
        // interleaver_impl
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
        // gray_demap_impl: inverse Gray, then +1
        val nMod = 1 shl sf
        return IntArray(out.size) { i ->
            var g = out[i]
            for (j in 1 until sf) g = g xor (out[i] shr j)
            Math.floorMod(g + 1, nMod)
        }
    }

    /**
     * Complex baseband of a whole frame (preamble, sync word, 2.25 downchirps, [symbols]) sampled at [rate] (in chips
     * per sample is bw / rate), starting [delayChips] late, with a carrier offset [cfoHz] and a transmitter clock
     * [ppm] fast (both carrier and chips), then [mixHz] of frequency shift and [tailChips] of silence.
     */
    fun waveform(
        symbols: IntArray, sf: Int, bw: Double, rate: Double,
        syncWord: Int = 0x2B, preamble: Int = 16, delayChips: Double = 0.0, cfoHz: Double = 0.0, ppm: Double = 0.0,
        mixHz: Double = 0.0, tailChips: Double = 2048.0, amplitude: Double = 1.0,
    ): Pair<DoubleArray, DoubleArray> {
        val n = 1 shl sf
        // (id, kind): kind 0 upchirp, 1 downchirp; the quarter downchirp is handled by the frame length.
        val ids = ArrayList<Pair<Int, Int>>()
        repeat(preamble) { ids += 0 to 0 }
        ids += (((syncWord shr 4) and 0xF) shl 3) to 0
        ids += ((syncWord and 0xF) shl 3) to 0
        repeat(3) { ids += 0 to 1 }
        val downEnd = (preamble + 4.25) * n
        val payloadStart = downEnd
        val frameChips = payloadStart + symbols.size * n
        val totalChips = delayChips + frameChips + tailChips
        val count = (totalChips * rate / bw).toInt()
        val re = DoubleArray(count)
        val im = DoubleArray(count)
        val chipRate = bw * (1 + ppm * 1e-6)
        val fc = cfoHz
        for (s in 0 until count) {
            val tSec = s / rate
            val chip = tSec * chipRate - delayChips
            var phase = 2 * PI * mixHz * tSec
            if (chip >= 0 && chip < frameChips) {
                val id: Int
                val down: Boolean
                val local: Double
                if (chip < payloadStart) {
                    val k = floor(chip / n).toInt()
                    if (chip >= (preamble + 2) * n) { id = 0; down = true } else { id = ids[k].first; down = false }
                    local = chip - k * n
                } else {
                    val k = floor((chip - payloadStart) / n).toInt()
                    id = symbols[k]; down = false
                    local = chip - payloadStart - k * n
                }
                // build_upchirp: e^{j2π(t²/2N + (id/N − 0.5)t)} with the fold at N − id.
                val f0 = if (local < n - id) id.toDouble() / n - 0.5 else id.toDouble() / n - 1.5
                var ph = 2 * PI * (local * local / (2.0 * n) + f0 * local)
                if (down) ph = -ph
                phase += ph + 2 * PI * fc * tSec
                re[s] = amplitude * cos(phase); im[s] = amplitude * sin(phase)
            }
        }
        return re to im
    }
}
