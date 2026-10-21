package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * ACARS, the text datalink of airliners (ARINC 618): AM on 131 MHz carrying MSK audio at 2400 bit/s (1200 and 2400 Hz),
 * 7-bit characters with odd parity sent least significant bit first, framed as
 * `+ * SYN SYN SOH mode address(7) ack label(2) block [STX text] ETX|ETB BCS(2) DEL`, the BCS a CRC-16 (Kermit) over
 * everything from the mode character to the suffix.
 */
data class AcarsMessage(
    val timeMs: Long,
    val freqMhz: Double,
    val mode: Char,
    val registration: String,
    val ack: Char,
    val label: String,
    val blockId: Char,
    /** Downlink (from the aircraft): message number and flight number open the text. */
    val msgNo: String?,
    val flight: String?,
    val text: String,
    val more: Boolean,
) {
    val downlink: Boolean get() = blockId in '0'..'9'
}

object Acars {
    const val SOH = 0x01
    const val STX = 0x02
    const val ETX = 0x03
    const val ETB = 0x17
    const val SYN = 0x16
    const val DEL = 0x7F

    /** Channels used in Europe; one 2.4 MS/s capture centred on 131.625 MHz covers the three of them. */
    val EuropeHz = listOf(131_525_000L, 131_725_000L, 131_825_000L)
    const val CenterHz = 131_625_000L

    fun crc(bytes: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) {
            c = c xor (bytes[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c and 0xFFFF
    }

    fun oddParity(b: Int): Boolean = Integer.bitCount(b and 0xFF) % 2 == 1

    /** Adds the odd-parity bit to a 7-bit character. */
    fun withParity(c: Int): Int = if (Integer.bitCount(c and 0x7F) % 2 == 1) c and 0x7F else (c and 0x7F) or 0x80

    /** Common labels, for the list (ARINC 620 and usual airline use). */
    val Labels = mapOf(
        "_d" to S(R.string.link_without_message), "Q0" to S(R.string.link_test), "H1" to S(R.string.crew_company_message), "5Z" to S(R.string.company_message),
        "SA" to S(R.string.link_status), "SQ" to S(R.string.ground_station_beacon), "QA" to S(R.string.departure_out), "QB" to S(R.string.take_off_off),
        "QC" to S(R.string.landing_on), "QD" to S(R.string.arrival_in), "QE" to S(R.string.oooi_times), "QF" to S(R.string.take_off), "QG" to S(R.string.return_to_gate),
        "10" to S(R.string.status), "12" to "Position", "15" to S(R.string.position_weather), "16" to "Position", "20" to S(R.string.delay_report),
        "2Z" to "Destination", "44" to "Position (FMS)", "80" to S(R.string.free_text), "83" to S(R.string.weather_request), "A9" to "ATIS",
        "B6" to S(R.string.clearance_ads_c), "BA" to S(R.string.clearance_request), "C1" to S(R.string.cockpit_message), "RA" to S(R.string.text_message_printer),
        "SG" to S(R.string.service_message),
    )

    /** Decodes a frame body (from the mode character to the suffix, parity bits included) once its BCS is checked. */
    fun parse(body: ByteArray, len: Int, freqMhz: Double, nowMs: Long): AcarsMessage? {
        if (len < 13) return null
        for (i in 0 until len) if (!oddParity(body[i].toInt())) return null
        fun ch(i: Int) = (body[i].toInt() and 0x7F).toChar()
        val suffix = body[len - 1].toInt() and 0x7F
        if (suffix != ETX && suffix != ETB) return null
        val mode = ch(0)
        val reg = (1..7).map { ch(it) }.joinToString("").trim('.', ' ')
        val ack = ch(8)
        val label = "${ch(9)}${ch(10)}"
        val block = ch(11)
        var text = ""
        if (len > 13 && (body[12].toInt() and 0x7F) == STX) {
            text = (13 until len - 1).map { ch(it) }.joinToString("")
        }
        val downlink = block in '0'..'9'
        var msgNo: String? = null
        var flight: String? = null
        if (downlink && text.length >= 10) {
            msgNo = text.substring(0, 4)
            flight = text.substring(4, 10).trim().ifEmpty { null }
            text = text.substring(10)
        }
        return AcarsMessage(nowMs, freqMhz, mode, reg, ack, label, block, msgNo, flight, text.trimEnd(), suffix == ETB)
    }

    /** Frame bytes for [body] (mode … text), as sent: preamble sync, SOH, parity, suffix, BCS and DEL. Used by the tests. */
    fun frame(mode: Char, reg: String, ack: Char, label: String, block: Char, text: String?): ByteArray {
        val chars = buildList {
            add(mode.code); reg.padStart(7, '.').take(7).forEach { add(it.code) }
            add(ack.code); label.forEach { add(it.code) }; add(block.code)
            if (text != null) { add(STX); text.forEach { add(it.code) } }
            add(ETX)
        }.map(::withParity)
        val body = ByteArray(chars.size) { chars[it].toByte() }
        val c = crc(body, 0, body.size)
        return byteArrayOf(withParity('+'.code).toByte(), withParity('*'.code).toByte(), withParity(SYN).toByte(), withParity(SYN).toByte(), withParity(SOH).toByte()) +
            body + byteArrayOf((c and 0xFF).toByte(), (c ushr 8).toByte(), withParity(DEL).toByte())
    }
}

/**
 * MSK demodulator and frame reader for one channel of AM audio at [audioRate]: tone energies at 1200 and 2400 Hz over one
 * bit, the bit clock taken where they differ most, then the bytes after the sync. The tone-to-bit rule is not taken for
 * granted: the sync is looked for with the tones read directly, inverted, and as changes, and the first that matches is
 * kept for the frame; the parity and the BCS then reject anything else.
 */
class AcarsDecoder(private val audioRate: Double, private val freqMhz: Double, private val onMessage: (AcarsMessage) -> Unit) {
    private val spb = Math.round(audioRate / 2400.0).toInt()
    // One period of each tone fits a whole number of samples at 48 kS/s: 40 for 1200 Hz, 20 for 2400 Hz.
    private val p1 = Math.round(audioRate / 1200.0).toInt()
    private val p2 = Math.round(audioRate / 2400.0).toInt()
    private val c1 = DoubleArray(p1) { cos(2 * PI * it / p1) }
    private val q1 = DoubleArray(p1) { sin(2 * PI * it / p1) }
    private val c2 = DoubleArray(p2) { cos(2 * PI * it / p2) }
    private val q2 = DoubleArray(p2) { sin(2 * PI * it / p2) }
    private val hist = DoubleArray(spb)
    private var n = 0L
    private var s1r = 0.0; private var s1i = 0.0; private var s2r = 0.0; private var s2i = 0.0
    private val phaseEnergy = DoubleArray(spb)
    private var dc = 0.0
    private var pow = 1e-6

    private class Reader {
        var reg = 0L
        var bits = 0
        var inFrame = false
        val buf = ByteArray(260)
        var len = 0
        var tail = -1
    }

    private val readers = Array(4) { Reader() }
    private var prevTone = 0

    fun feed(audio: FloatArray, count: Int) {
        for (k in 0 until count) {
            // Remove the carrier (DC, corner near 40 Hz, well below the tones) and normalise the level.
            if (n == 0L) dc = audio[k].toDouble()
            dc += (audio[k] - dc) * 0.005
            val x0 = audio[k] - dc
            pow += (x0 * x0 - pow) * 0.0005
            val x = x0 / sqrt(pow + 1e-12)
            val idx = (n % spb).toInt()
            val old = hist[idx]
            hist[idx] = x
            // Sliding correlations over the last bit: add the new sample, drop the one a bit ago (same table index for 2400 Hz).
            val a1 = (n % p1).toInt()
            val o1 = ((n - spb) % p1 + p1).toInt() % p1
            val a2 = (n % p2).toInt()
            s1r += x * c1[a1] - old * c1[o1]; s1i -= x * q1[a1] - old * q1[o1]
            s2r += (x - old) * c2[a2]; s2i -= (x - old) * q2[a2]
            n++
            if (n % 4_800_000L == 0L) recompute()
            val m = (s2r * s2r + s2i * s2i) - (s1r * s1r + s1i * s1i)
            phaseEnergy[idx] = phaseEnergy[idx] * 0.995 + kotlin.math.abs(m) * 0.005
            // Sample at the phase where the two tones are told apart best.
            var best = 0
            for (p in 1 until spb) if (phaseEnergy[p] > phaseEnergy[best]) best = p
            if (idx == best) bit(if (m > 0) 1 else 0)
        }
    }

    /** Running sums drift with rounding over hours: rebuild them from the last bit of history. */
    private fun recompute() {
        s1r = 0.0; s1i = 0.0; s2r = 0.0; s2i = 0.0
        for (j in 0 until spb) {
            val t = n - spb + j
            val v = hist[(t % spb).toInt()]
            s1r += v * c1[(t % p1).toInt()]; s1i -= v * q1[(t % p1).toInt()]
            s2r += v * c2[(t % p2).toInt()]; s2i -= v * q2[(t % p2).toInt()]
        }
    }

    private fun bit(tone: Int) {
        val cand = intArrayOf(tone, 1 - tone, if (tone == prevTone) 1 else 0, if (tone != prevTone) 1 else 0)
        prevTone = tone
        for (h in 0 until 4) step(readers[h], cand[h])
    }

    private fun step(r: Reader, b: Int) {
        // Least significant bit first: each new bit enters at the top of the byte.
        r.reg = ((r.reg ushr 1) or (b.toLong() shl 39)) and 0xFF_FFFF_FFFFL
        if (!r.inFrame) {
            val sync = (r.reg ushr 0) and 0xFF_FFFF_FFFFL
            if (sync == SYNC) { r.inFrame = true; r.len = 0; r.bits = 0; r.tail = -1 }
            return
        }
        if (++r.bits < 8) return
        r.bits = 0
        val byte = ((r.reg ushr 32) and 0xFF).toInt()
        if (r.len >= r.buf.size) { r.inFrame = false; return }
        r.buf[r.len++] = byte.toByte()
        val c = byte and 0x7F
        if (r.tail < 0 && r.len > 12 && (c == Acars.ETX || c == Acars.ETB)) r.tail = r.len
        if (r.tail >= 0 && r.len == r.tail + 2) {
            r.inFrame = false
            val crc = Acars.crc(r.buf, 0, r.tail)
            val got = (r.buf[r.tail].toInt() and 0xFF) or ((r.buf[r.tail + 1].toInt() and 0xFF) shl 8)
            if (crc == got) Acars.parse(r.buf, r.tail, freqMhz, System.currentTimeMillis())?.let { m -> readers.forEach { it.inFrame = false }; onMessage(m) }
        }
    }

    private companion object {
        /** `+ * SYN SYN SOH` with parity, least significant bit first, as it ends in a 40-bit shift register. */
        val SYNC: Long = run {
            val bytes = listOf('+'.code, '*'.code, Acars.SYN, Acars.SYN, Acars.SOH).map { Acars.withParity(it).toLong() }
            bytes.foldIndexed(0L) { i, acc, b -> acc or (b shl (8 * i)) }
        }
    }
}

/**
 * HackRF bytes at [SAMPLE_RATE] centred on [Acars.CenterHz] to messages on the three European channels: each channel is
 * mixed to zero, filtered down to 48 kS/s, AM-demodulated and handed to its [AcarsDecoder].
 */
class AcarsReceiver(channelsHz: List<Long> = Acars.EuropeHz, centerHz: Long = Acars.CenterHz, private val onMessage: (AcarsMessage) -> Unit) {
    private class Channel(offsetHz: Double, freqMhz: Double, onMessage: (AcarsMessage) -> Unit) {
        val w = -2 * PI * offsetHz / SAMPLE_RATE
        var ph = 0.0
        val d1 = FloatDecimator(10, 0.04, taps = 48)
        val d2 = FloatDecimator(5, 0.03, taps = 64)
        val decoder = AcarsDecoder(SAMPLE_RATE / 50.0, freqMhz, onMessage)
        val mr = FloatArray(131072)
        val mi = FloatArray(131072)
        val ar = FloatArray(16384)
        val ai = FloatArray(16384)
        val br = FloatArray(4096)
        val bi = FloatArray(4096)
        val audio = FloatArray(4096)
    }

    private val channels = channelsHz.map { Channel((it - centerHz).toDouble(), it / 1e6, onMessage) }
    private val re = FloatArray(131072)
    private val im = FloatArray(131072)

    fun feed(buf: ByteArray, len: Int) {
        val n = len / 2
        for (i in 0 until n) { re[i] = buf[2 * i] / 128f; im[i] = buf[2 * i + 1] / 128f }
        feedFloat(re, im, n)
    }

    fun feedFloat(re: FloatArray, im: FloatArray, n: Int) {
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
            for (i in 0 until m) c.audio[i] = sqrt(c.br[i] * c.br[i] + c.bi[i] * c.bi[i])
            c.decoder.feed(c.audio, m)
        }
    }

    companion object {
        const val SAMPLE_RATE = 2_400_000
    }
}
