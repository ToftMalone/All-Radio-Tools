package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * POCSAG pagers (ITU-R M.584): 2-FSK at ±4.5 kHz, 512, 1200 or 2400 bit/s. Batches open with a sync codeword and hold
 * eight frames of two 32-bit codewords, each a BCH(31,21) word plus even parity that corrects up to two bit errors.
 */

data class PocsagMessage(
    /** Pager address: the 18 address bits and the frame number. */
    val ric: Int,
    /** Function bits 0 to 3, shown as A to D on pagers. */
    val function: Int,
    val kind: Kind,
    val text: String,
    val baud: Int,
    val freqMhz: Double,
    /** Bit errors fixed by the BCH code across the message. */
    val corrected: Int,
    val timeMs: Long = 0,
) {
    enum class Kind { Tone, Numeric, Alpha }

    val functionLetter: Char get() = 'A' + function
}

object Pocsag {
    const val SYNC = 0x7CD215D8
    const val IDLE = 0x7A89C197
    /** x^10 + x^9 + x^8 + x^6 + x^5 + x^3 + 1 */
    private const val GEN = 0x769
    private const val NUMERIC = "0123456789*U -)("

    /** Remainder of the 31 BCH bits (codeword bits 31 to 1) divided by the generator. */
    fun syndrome(cw: Int): Int {
        var v = cw ushr 1
        for (bit in 30 downTo 10) if (v and (1 shl bit) != 0) v = v xor (GEN shl (bit - 10))
        return v and 0x3FF
    }

    /** A codeword from 21 data bits: the BCH check bits, then even parity. */
    fun encode(data21: Int): Int {
        val shifted = (data21 and 0x1FFFFF) shl 10
        var v = shifted
        for (bit in 30 downTo 10) if (v and (1 shl bit) != 0) v = v xor (GEN shl (bit - 10))
        val cw = (shifted or (v and 0x3FF)) shl 1
        return cw or (Integer.bitCount(cw) and 1)
    }

    /** Error patterns over bits 31 to 1 by syndrome: every single and double error. */
    private val fixes: Map<Int, Int> = HashMap<Int, Int>().apply {
        for (i in 1..31) {
            put(syndrome(1 shl i), 1 shl i)
            for (j in i + 1..31) put(syndrome((1 shl i) or (1 shl j)), (1 shl i) or (1 shl j))
        }
    }

    /** The codeword with up to two bit errors fixed, and how many there were; null when it cannot be fixed. */
    fun correct(cw: Int): Pair<Int, Int>? {
        var fixed = cw
        var errors = 0
        val s = syndrome(cw)
        if (s != 0) {
            val e = fixes[s] ?: return null
            fixed = cw xor e
            errors = Integer.bitCount(e)
        }
        if (Integer.bitCount(fixed) and 1 != 0) {
            if (errors >= 2) return null
            fixed = fixed xor 1
            errors++
        }
        return fixed to errors
    }

    /** Digits: four bits each, least significant first. */
    fun numeric(bits: List<Boolean>): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 4 <= bits.size) {
            var v = 0
            for (k in 0 until 4) if (bits[i + k]) v = v or (1 shl k)
            sb.append(NUMERIC[v])
            i += 4
        }
        return sb.toString().trim()
    }

    /** 7-bit characters, least significant bit first, running across codewords. */
    fun alpha(bits: List<Boolean>): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 7 <= bits.size) {
            var v = 0
            for (k in 0 until 7) if (bits[i + k]) v = v or (1 shl k)
            sb.append(v.toChar())
            i += 7
        }
        // End-of-text and padding characters close the message.
        return sb.toString().trimEnd { it.code < 32 }.map { if (it.code < 32 || it.code == 127) '·' else it }.joinToString("")
    }

    /** Numeric for function A when it reads as digits, otherwise text when it reads as text. */
    fun content(bits: List<Boolean>, function: Int): Pair<PocsagMessage.Kind, String> {
        if (bits.isEmpty()) return PocsagMessage.Kind.Tone to ""
        val num = numeric(bits)
        val alpha = alpha(bits)
        val numClean = num.isNotEmpty() && 'U' !in num
        val alphaClean = alpha.isNotEmpty() && alpha.count { it == '·' } * 10 <= alpha.length
        return when {
            function == 0 && numClean -> PocsagMessage.Kind.Numeric to num
            alphaClean -> PocsagMessage.Kind.Alpha to alpha
            numClean -> PocsagMessage.Kind.Numeric to num
            else -> PocsagMessage.Kind.Alpha to alpha
        }
    }
}

/**
 * Codewords after a sync word, in either FSK polarity: a batch of 16, then either another sync word or the end of the
 * transmission. A message runs from its address codeword to the next address or idle codeword.
 */
class PocsagFramer(private val baud: Int, private val freqMhz: Double, private val onMessage: (PocsagMessage) -> Unit) {
    private var reg = 0
    private var inverted = false
    private var synced = false
    private var bitCount = 0
    private var cwIndex = 0
    private var expectSync = false
    private var firstBatch = true
    private var batchBad = 0
    private var batchFixed = 0

    private var ric = -1
    private var function = 0
    private var corrected = 0
    private val data = ArrayList<Boolean>()
    private val pending = ArrayList<PocsagMessage>()

    fun bit(b: Boolean) {
        reg = (reg shl 1) or (if (b) 1 else 0)
        if (!synced) {
            when {
                Integer.bitCount(reg xor Pocsag.SYNC) <= 1 -> { synced = true; inverted = false; startBatch(first = true) }
                Integer.bitCount(reg.inv() xor Pocsag.SYNC) <= 1 -> { synced = true; inverted = true; startBatch(first = true) }
            }
            return
        }
        if (++bitCount < 32) return
        bitCount = 0
        val cw = if (inverted) reg.inv() else reg
        if (expectSync) {
            if (Integer.bitCount(cw xor Pocsag.SYNC) <= 3) { release(); startBatch(first = false) } else end()
            return
        }
        codeword(cw)
        if (++cwIndex == 16) expectSync = true
    }

    private fun startBatch(first: Boolean) {
        bitCount = 0; cwIndex = 0; expectSync = false; batchBad = 0; batchFixed = 0
        firstBatch = first
    }

    private fun codeword(raw: Int) {
        val fixed = Pocsag.correct(raw)
        if (fixed == null) {
            batchBad++
            if (ric >= 0) corrected += 3 // a codeword lost inside a message
            return
        }
        val (cw, errors) = fixed
        batchFixed += errors
        when {
            cw == Pocsag.IDLE -> flush()
            cw ushr 31 == 0 -> {
                flush()
                ric = (((cw ushr 13) and 0x3FFFF) shl 3) or (cwIndex / 2)
                function = (cw ushr 11) and 3
                corrected = errors
            }
            ric >= 0 -> {
                for (k in 30 downTo 11) data += (cw ushr k) and 1 == 1
                corrected += errors
            }
        }
    }

    private fun flush() {
        if (ric < 0) return
        val (kind, text) = Pocsag.content(data, function)
        pending += PocsagMessage(ric, function, kind, text, baud, freqMhz, corrected)
        ric = -1; data.clear(); corrected = 0
    }

    /** Messages of a batch go out once the next sync word confirms the lock, or at the end if the batch read cleanly. */
    private fun release() {
        pending.forEach(onMessage)
        pending.clear()
    }

    private fun end() {
        flush()
        // A lone batch after a chance sync match is mostly unfixable codewords: drop it.
        if (!firstBatch || batchBad == 0 && batchFixed <= 4) release() else pending.clear()
        synced = false
        reg = 0
    }
}

/**
 * Bits from an FM discriminator at [sampleRate]: the slicer level follows the signal's mean, and a clock nudged at
 * every transition integrates each bit.
 */
class PocsagBitClock(sampleRate: Double, baud: Int, private val onBit: (Boolean) -> Unit) {
    private val step = baud / sampleRate
    private val meanRate = (baud / sampleRate / 64).toFloat()
    private var phase = 0.0
    private var sum = 0f
    private var mean = 0f
    private var prev = 0f

    fun feed(x: FloatArray, n: Int) {
        for (i in 0 until n) {
            mean += (x[i] - mean) * meanRate
            val v = x[i] - mean
            if ((v > 0) != (prev > 0)) {
                // Transitions belong on bit edges: pull the clock a quarter of the way there.
                val err = if (phase < 0.5) phase else phase - 1
                phase -= err * 0.25
            }
            prev = v
            sum += v
            phase += step
            if (phase >= 1) {
                phase -= 1
                onBit(sum > 0)
                sum = 0f
            }
        }
    }
}

/**
 * HackRF bytes at 2 MS/s in, pager messages out, for several channels at once: each is shifted to 0 Hz, filtered down
 * to 25 kS/s, FM-demodulated and read at 512, 1200 and 2400 bit/s together.
 */
class PocsagReceiver(channelsHz: List<Long>, val centerHz: Long = centre(channelsHz), onMessage: (PocsagMessage) -> Unit) {
    private class Channel(offsetHz: Double, freqMhz: Double, onMessage: (PocsagMessage) -> Unit) {
        val w = -2 * PI * offsetHz / SAMPLE_RATE
        var ph = 0.0
        val d1 = FloatDecimator(10, 0.02, taps = 48)
        val d2 = FloatDecimator(8, 0.045, taps = 128)
        val mr = FloatArray(131072)
        val mi = FloatArray(131072)
        val ar = FloatArray(16384)
        val ai = FloatArray(16384)
        val br = FloatArray(2048)
        val bi = FloatArray(2048)
        val fm = FloatArray(2048)
        var pr = 0f
        var pi = 0f
        val clocks = BAUDS.map { baud ->
            val framer = PocsagFramer(baud, freqMhz, onMessage)
            PocsagBitClock(SAMPLE_RATE / 80.0, baud) { framer.bit(it) }
        }
    }

    private val recent = HashMap<String, Long>()
    private val channels = channelsHz.map { hz ->
        Channel((hz - centerHz).toDouble(), hz / 1e6) { m ->
            // Paging networks send the same message again and from several transmitters: keep the first.
            val key = "${m.ric}/${m.function}/${m.text}"
            val now = System.currentTimeMillis()
            val last = recent[key]
            recent[key] = now
            if (recent.size > 512) recent.entries.removeAll { now - it.value > 120_000 }
            if (last == null || now - last > 60_000) onMessage(m.copy(timeMs = now))
        }
    }
    private val re = FloatArray(131072)
    private val im = FloatArray(131072)

    fun feed(buf: ByteArray, len: Int) {
        val n = len / 2
        for (i in 0 until n) { re[i] = buf[2 * i] / 128f; im[i] = buf[2 * i + 1] / 128f }
        for (c in channels) {
            val cw = cos(c.w); val sw = sin(c.w)
            var pr = cos(c.ph); var pi = sin(c.ph)
            for (i in 0 until n) {
                c.mr[i] = re[i] * pr.toFloat() - im[i] * pi.toFloat()
                c.mi[i] = re[i] * pi.toFloat() + im[i] * pr.toFloat()
                val t = pr * cw - pi * sw
                pi = pr * sw + pi * cw
                pr = t
            }
            c.ph = (c.ph + c.w * n) % (2 * PI)
            val k = c.d1.process(c.mr, c.mi, n, c.ar, c.ai)
            val m = c.d2.process(c.ar, c.ai, k, c.br, c.bi)
            for (i in 0 until m) {
                c.fm[i] = atan2(c.bi[i] * c.pr - c.br[i] * c.pi, c.br[i] * c.pr + c.bi[i] * c.pi)
                c.pr = c.br[i]; c.pi = c.bi[i]
            }
            for (clock in c.clocks) clock.feed(c.fm, m)
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        val BAUDS = listOf(512, 1200, 2400)
        /** Widest span of channels heard at once. */
        const val MAX_SPAN_HZ = 1_200_000L

        /** Tuned 250 kHz above the middle of the channels: the HackRF's DC spike stays outside every one. */
        fun centre(channelsHz: List<Long>): Long = (channelsHz.min() + channelsHz.max()) / 2 + 250_000
    }
}

/** Paging networks, with the channels heard together. */
data class PocsagNetwork(val name: String, val channelsHz: List<Long>)

object PocsagNetworks {
    /** e*Message's channels (ARCEP decision 2001-308); only some are still on air. */
    val France = PocsagNetwork("🇫🇷 e*Message", listOf(466_025_000L, 466_050_000L, 466_075_000L, 466_175_000L, 466_206_250L))
    /** e*Message Germany (Bundesnetzagentur, 2026). */
    val Germany = PocsagNetwork("🇩🇪 e*Message", listOf(465_970_000L, 466_075_000L, 466_230_000L))
    /** German emergency services pagers on e*Message's BOS channel. */
    val GermanyBos = PocsagNetwork("🇩🇪 BOS", listOf(448_425_000L))
    /** DAPNET, the radio amateurs' paging network. */
    val Dapnet = PocsagNetwork("DAPNET", listOf(439_987_500L))

    val all = listOf(France, Germany, GermanyBos, Dapnet)
}
