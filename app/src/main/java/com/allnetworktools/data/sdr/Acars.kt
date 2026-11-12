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

    /**
     * Channels used in Europe; one 2.4 MS/s capture centred on 131.600 MHz covers the three of them. The centre keeps
     * them off the receiver's DC spike, and no channel falls on another's mirror image (-75, +125 and +225 kHz).
     */
    val EuropeHz = listOf(131_525_000L, 131_725_000L, 131_825_000L)
    const val CenterHz = 131_600_000L

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

    /**
     * Checks the BCS of `buf[0 until end]` (the two BCS bytes follow) and, when it fails, mends up to two characters
     * whose parity is wrong by flipping one bit in each and checking the BCS again, as ground stations do. At most 64
     * tries, so a wrong frame passes for mended about once in a thousand; trying every value would be one in four.
     */
    fun mend(buf: ByteArray, end: Int): Boolean {
        val got = (buf[end].toInt() and 0xFF) or ((buf[end + 1].toInt() and 0xFF) shl 8)
        if (crc(buf, 0, end) == got) return true
        val bad = (0 until end).filter { !oddParity(buf[it].toInt()) }
        if (bad.isEmpty() || bad.size > 2) return false
        val keep = bad.map { buf[it] }
        val first = keep[0].toInt()
        for (i in 0 until 8) {
            buf[bad[0]] = (first xor (1 shl i)).toByte()
            if (bad.size == 1) { if (crc(buf, 0, end) == got) return true; continue }
            val second = keep[1].toInt()
            for (j in 0 until 8) { buf[bad[1]] = (second xor (1 shl j)).toByte(); if (crc(buf, 0, end) == got) return true }
        }
        bad.forEachIndexed { k, at -> buf[at] = keep[k] }
        return false
    }

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
 * MSK demodulator and frame reader for one channel of AM audio at [audioRate]. The audio is mixed down by 1800 Hz, the
 * centre between the two tones, so 1200 Hz and 2400 Hz become -600 and +600 Hz and the phase turns a quarter turn per
 * bit. Undoing that quarter turn leaves two opposite points, read coherently as in acarsdec: the data bit is that
 * point, so a 2400 Hz bit repeats the previous bit and a 1200 Hz bit inverts it. The reference axis follows the
 * signal, so a tone a little off frequency does no harm, and both polarities are read. The bit clock is not guessed:
 * every sampling phase within a bit reads its own bit stream, and the one that finds the sync reads the frame. Parity
 * and the BCS then reject anything else, after trying to mend one or two bad characters with the BCS.
 */
class AcarsDecoder(private val audioRate: Double, private val freqMhz: Double, private val onMessage: (AcarsMessage) -> Unit) {
    private val spb = Math.round(audioRate / 2400.0).toInt()
    private val lo = 2 * PI * 1800.0 / audioRate
    private val taps = FloatArray(LPF_TAPS).also { h ->
        val fc = 1700.0 / audioRate
        var sum = 0.0
        val raw = DoubleArray(LPF_TAPS) { i ->
            val m = i - (LPF_TAPS - 1) / 2.0
            val s = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            s * (0.42 - 0.5 * cos(2 * PI * i / (LPF_TAPS - 1)) + 0.08 * cos(4 * PI * i / (LPF_TAPS - 1)))
        }
        sum = raw.sum()
        for (i in h.indices) h[i] = (raw[i] / sum).toFloat()
    }
    private val br = FloatArray(LPF_TAPS * 2)
    private val bi = FloatArray(LPF_TAPS * 2)
    private var bp = 0
    /** For each sampling phase: bits read so far (for the quarter turn) and the reference axis. */
    private val count = IntArray(spb)
    private val axisR = FloatArray(spb) { 1f }
    private val axisI = FloatArray(spb)
    private var n = 0L
    private var dc = 0.0
    private var ph = 0.0

    private class Reader {
        var reg = 0L
        var bits = 0
        var inFrame = false
        val buf = ByteArray(260)
        var len = 0
        var tail = -1
    }

    /** For each sampling phase: the bits as read, and inverted. */
    private val readers = Array(spb) { Array(2) { Reader() } }

    fun feed(audio: FloatArray, count: Int) {
        for (k in 0 until count) {
            // Remove the carrier (DC, corner near 40 Hz, well below the tones).
            if (n == 0L) dc = audio[k].toDouble()
            dc += (audio[k] - dc) * 0.005
            val x = (audio[k] - dc).toFloat()
            br[bp] = x * cos(ph).toFloat(); br[bp + LPF_TAPS] = br[bp]
            bi[bp] = -x * sin(ph).toFloat(); bi[bp + LPF_TAPS] = bi[bp]
            ph += lo
            if (ph > 2 * PI) ph -= 2 * PI
            bp++
            if (bp == LPF_TAPS) bp = 0
            var r = 0f
            var i = 0f
            for (j in 0 until LPF_TAPS) { r += taps[j] * br[bp + j]; i += taps[j] * bi[bp + j] }
            val slot = (n % spb).toInt()
            n++
            if (n > LPF_TAPS) bit(slot, r, i)
        }
    }

    private fun bit(p: Int, r: Float, i: Float) {
        // Undo the quarter turn per bit: what is left lies on one axis, one way for 1 and the other for 0.
        val (wr, wi) = when (count[p] and 3) { 0 -> r to i; 1 -> i to -r; 2 -> -r to -i; else -> -i to r }
        count[p]++
        val mag = sqrt(wr * wr + wi * wi)
        if (mag < 1e-9f) return
        val dot = wr * axisR[p] + wi * axisI[p]
        val b = if (dot > 0) 1 else 0
        // Follow the axis with each decision, so it never jumps half a turn.
        val s = if (b == 1) 1f else -1f
        var ar = 0.9f * axisR[p] + 0.1f * s * wr / mag
        var ai = 0.9f * axisI[p] + 0.1f * s * wi / mag
        val am = sqrt(ar * ar + ai * ai)
        if (am > 1e-6f) { ar /= am; ai /= am } else { ar = wr / mag; ai = wi / mag }
        axisR[p] = ar; axisI[p] = ai
        step(readers[p][0], b)
        step(readers[p][1], 1 - b)
    }

    private fun step(r: Reader, b: Int) {
        // Least significant bit first: each new bit enters at the top of the byte.
        r.reg = ((r.reg ushr 1) or (b.toLong() shl 39)) and 0xFF_FFFF_FFFFL
        if (!r.inFrame) {
            // Only SYN SYN SOH, as acarsdec: the "+ *" bit-sync characters go out while the transmitter is still
            // keying up and often arrive damaged, so asking for them lost the start of real messages.
            if ((r.reg ushr 16) == SYNC) { r.inFrame = true; r.len = 0; r.bits = 0; r.tail = -1 }
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
            if (Acars.mend(r.buf, r.tail)) Acars.parse(r.buf, r.tail, freqMhz, System.currentTimeMillis())?.let { m ->
                // The neighbouring phases read the same frame: drop their copies.
                for (ps in readers) for (o in ps) o.inFrame = false
                onMessage(m)
            }
        }
    }

    private companion object {
        const val LPF_TAPS = 48

        /** `SYN SYN SOH` with parity, least significant bit first, as it ends in the top 24 bits of the shift register. */
        val SYNC: Long = run {
            val bytes = listOf(Acars.SYN, Acars.SYN, Acars.SOH).map { Acars.withParity(it).toLong() }
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
