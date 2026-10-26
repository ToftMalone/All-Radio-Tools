package com.allnetworktools.data.sdr

/**
 * LoRa bit-level coding, ported from the open-source gr-lora_sdr receiver (Tapparel et al., EPFL), which
 * interoperates with Semtech SX127x/SX126x chips: whitening, explicit header, Hamming codes, diagonal
 * interleaving and Gray mapping.
 */
object LoraPhy {
    /** Whitening sequence: LFSR x⁸+x⁶+x⁵+x⁴+1 seeded with 0xFF (identical to gr-lora_sdr's table). */
    val whitening: IntArray = IntArray(255).also { t ->
        var s = 0xFF
        for (i in t.indices) {
            t[i] = s
            val b = ((s shr 7) xor (s shr 5) xor (s shr 4) xor (s shr 3)) and 1
            s = ((s shl 1) or b) and 0xFF
        }
    }

    /** Symbol value from an FFT bin: undo the +1 shift, divide by 4 in reduced-rate blocks, then Gray-map. */
    fun binToValue(bin: Int, sf: Int, reduced: Boolean): Int {
        val n = 1 shl sf
        var v = Math.floorMod(bin - 1, n)
        if (reduced) v /= 4
        return v xor (v shr 1)
    }

    /**
     * One interleaving block: [symbols] (cwLen values of sfApp bits) back to sfApp codewords of cwLen bits.
     * deinter[(i - j - 1) mod sfApp][i] = inter[i][j], bits MSB first.
     */
    fun deinterleave(symbols: IntArray, sfApp: Int, cwLen: Int): IntArray {
        val out = IntArray(sfApp)
        for (i in 0 until cwLen) {
            for (j in 0 until sfApp) {
                val bit = (symbols[i] shr (sfApp - 1 - j)) and 1
                val row = Math.floorMod(i - j - 1, sfApp)
                if (bit == 1) out[row] = out[row] or (1 shl (cwLen - 1 - i))
            }
        }
        return out
    }

    /** Hard Hamming decoding of a codeword of 4 + [cr] bits into its data nibble, correcting single errors for CR 4/7 and 4/8. */
    fun hammingDecode(codeword: Int, cr: Int): Int {
        val len = cr + 4
        val c = IntArray(len) { (codeword shr (len - 1 - it)) and 1 } // MSB first
        val d = intArrayOf(c[3], c[2], c[1], c[0]) // data nibble, MSB first
        val correct = when (cr) {
            4 -> c.sum() % 2 == 1 // an even number of flipped bits cannot be corrected
            3 -> true
            else -> false
        }
        if (correct) {
            val s0 = c[0] xor c[1] xor c[2] xor c[4]
            val s1 = c[1] xor c[2] xor c[3] xor c[5]
            val s2 = c[0] xor c[1] xor c[3] xor c[6]
            when (s0 + (s1 shl 1) + (s2 shl 2)) {
                5 -> d[3] = d[3] xor 1
                7 -> d[2] = d[2] xor 1
                3 -> d[1] = d[1] xor 1
                6 -> d[0] = d[0] xor 1
            }
        }
        return (d[0] shl 3) or (d[1] shl 2) or (d[2] shl 1) or d[3]
    }

    data class Header(val payloadLen: Int, val cr: Int, val hasCrc: Boolean)

    /** Explicit header from its 5 nibbles; null when the checksum does not match. */
    fun header(n: IntArray): Header? {
        fun b(x: Int, bit: Int) = (x shr bit) and 1
        val c4 = b(n[0], 3) xor b(n[0], 2) xor b(n[0], 1) xor b(n[0], 0)
        val c3 = b(n[0], 3) xor b(n[1], 3) xor b(n[1], 2) xor b(n[1], 1) xor b(n[2], 0)
        val c2 = b(n[0], 2) xor b(n[1], 3) xor b(n[1], 0) xor b(n[2], 3) xor b(n[2], 1)
        val c1 = b(n[0], 1) xor b(n[1], 2) xor b(n[1], 0) xor b(n[2], 2) xor b(n[2], 1) xor b(n[2], 0)
        val c0 = b(n[0], 0) xor b(n[1], 1) xor b(n[2], 3) xor b(n[2], 2) xor b(n[2], 1) xor b(n[2], 0)
        val expected = (c4 shl 4) or (c3 shl 3) or (c2 shl 2) or (c1 shl 1) or c0
        val got = ((n[3] and 1) shl 4) or n[4]
        val len = (n[0] shl 4) or n[1]
        val cr = n[2] shr 1
        if (got != expected || len == 0 || cr !in 1..4) return null
        return Header(len, cr, n[2] and 1 == 1)
    }

    /** CRC-16/CCITT (poly 0x1021, init 0) as LoRa computes it over all bytes but the last two. */
    fun crc16(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            var b = data[i].toInt() and 0xFF
            repeat(8) {
                crc = if (((crc and 0x8000) shr 8) xor (b and 0x80) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
                b = b shl 1
            }
        }
        return crc
    }

    /** LoRa payload CRC: CRC16 of payload[0 .. len-2) XOR the last two payload bytes. */
    fun payloadCrc(payload: ByteArray): Int {
        val n = payload.size
        if (n < 2) return 0
        return crc16(payload, n - 2) xor (payload[n - 1].toInt() and 0xFF) xor ((payload[n - 2].toInt() and 0xFF) shl 8)
    }

    /**
     * Number of payload symbols after the 8 header symbols. With the low-data-rate optimisation ([ldro]) every
     * block carries sf − 2 codewords, like the header block.
     */
    fun payloadSymbols(sf: Int, payloadLen: Int, cr: Int, hasCrc: Boolean, ldro: Boolean = false): Int {
        val nibbles = 5 + 2 * payloadLen + (if (hasCrc) 4 else 0)
        val rest = nibbles - (sf - 2)
        val rows = if (ldro) sf - 2 else sf
        return if (rest <= 0) 0 else ((rest + rows - 1) / rows) * (cr + 4)
    }

    /** Semtech chips switch the low-data-rate optimisation on when a symbol lasts 16 ms or more. */
    fun lowDataRate(sf: Int, bandwidthHz: Double): Boolean = (1 shl sf) / bandwidthHz >= 0.016

    /** The explicit header read from the first 8 symbols (FFT bins), with the payload nibbles that share its block. */
    fun readHeader(bins: List<Int>, sf: Int): Pair<Header, List<Int>>? {
        if (bins.size < 8) return null
        val vals = IntArray(8) { binToValue(bins[it], sf, reduced = true) }
        val nib = deinterleave(vals, sf - 2, 8).map { hammingDecode(it, 4) }
        val h = header(nib.take(5).toIntArray()) ?: return null
        return h to nib.drop(5)
    }

    class Decoded(val header: Header, val payload: ByteArray, val crcOk: Boolean)

    /** A whole frame from its symbols (FFT bins relative to the preamble); null when the header does not check out. */
    fun decode(bins: List<Int>, sf: Int, ldro: Boolean): Decoded? {
        val (h, first) = readHeader(bins, sf) ?: return null
        val nibbles = ArrayList(first)
        val block = h.cr + 4
        val rows = if (ldro) sf - 2 else sf
        var i = 8
        while (i + block <= bins.size) {
            val vals = IntArray(block) { binToValue(bins[i + it], sf, reduced = ldro) }
            deinterleave(vals, rows, block).forEach { nibbles += hammingDecode(it, h.cr) }
            i += block
        }
        val len = h.payloadLen
        if (nibbles.size < 2 * len + (if (h.hasCrc) 4 else 0)) return null
        val payload = ByteArray(len) { k ->
            val w = whitening[k]
            val lo = nibbles[2 * k] xor (w and 0x0F)
            val hi = nibbles[2 * k + 1] xor (w shr 4)
            ((hi shl 4) or lo).toByte()
        }
        val crcOk = if (h.hasCrc) {
            val got = nibbles[2 * len] or (nibbles[2 * len + 1] shl 4) or (nibbles[2 * len + 2] shl 8) or (nibbles[2 * len + 3] shl 12)
            got == payloadCrc(payload)
        } else true
        return Decoded(h, payload, crcOk)
    }
}
