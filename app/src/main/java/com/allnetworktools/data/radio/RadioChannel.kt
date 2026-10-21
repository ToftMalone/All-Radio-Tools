package com.allnetworktools.data.radio

import kotlin.math.roundToInt
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Sub-audible signalling that opens a squelch (receive) or is sent with the voice (transmit). */
sealed interface Tone {
    data object None : Tone
    data class Ctcss(val hz: Double) : Tone
    data class Dcs(val code: Int, val inverted: Boolean = false) : Tone

    val label: String
        get() = when (this) {
            None -> S(R.string.none)
            is Ctcss -> "%.1f Hz".format(java.util.Locale.getDefault(), hz)
            is Dcs -> "D%03dN".format(code).let { if (inverted) it.dropLast(1) + "I" else it }
        }
}

enum class RadioPower(val label: String) { High(S(R.string.high)), Medium(S(R.string.medium)), Low(S(R.string.low)) }

/** One memory of a handheld, in terms that do not depend on the radio model. */
data class RadioChannel(
    /** 0-based memory number. */
    val slot: Int,
    val rxHz: Long,
    /** Null: the radio never transmits on this memory (listen only). */
    val txHz: Long?,
    val name: String,
    val rxTone: Tone = Tone.None,
    val txTone: Tone = Tone.None,
    val power: RadioPower = RadioPower.High,
    /** 25 kHz (wide) or 12.5 kHz (narrow) deviation. */
    val wide: Boolean = true,
    val scan: Boolean = true,
) {
    val transmits: Boolean get() = txHz != null
    val offsetHz: Long? get() = txHz?.let { it - rxHz }
}

object RadioTones {
    val ctcss: List<Double> = listOf(
        67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5, 107.2, 110.9, 114.8, 118.8, 123.0, 127.3,
        131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5, 167.9, 171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8,
        196.6, 199.5, 203.5, 206.5, 210.7, 218.1, 225.7, 229.1, 233.6, 241.8, 250.3, 254.1,
    )

    /** The 104 standard digital codes plus 645, in the order the radios number them. */
    val dcs: List<Int> = (
        listOf(
            23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74, 114, 115, 116, 122, 125, 131, 132, 134, 143, 145, 152, 155,
            156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251, 252, 255, 261, 263, 265, 266, 271, 274, 306, 311,
            315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371, 411, 412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465,
            466, 503, 506, 516, 523, 526, 532, 546, 565, 606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712, 723, 731, 732, 734, 743, 754,
        ) + 645
        ).sorted()

    /** Value stored in the radio's 16-bit tone field: tenths of a hertz for CTCSS, an index (+ 0x69 when inverted) for DCS. */
    fun encode(t: Tone): Int = when (t) {
        Tone.None -> 0
        is Tone.Ctcss -> (t.hz * 10).roundToInt()
        is Tone.Dcs -> (dcs.indexOf(t.code).coerceAtLeast(0) + 1) + if (t.inverted) 0x69 else 0
    }

    fun decode(v: Int): Tone = when {
        v == 0 || v == 0xFFFF -> Tone.None
        v >= 0x0258 -> Tone.Ctcss(v / 10.0)
        else -> {
            val inverted = v >= 0x6A
            dcs.getOrNull(if (inverted) v - 0x6A else v - 1)?.let { Tone.Dcs(it, inverted) } ?: Tone.None
        }
    }
}

/** Little-endian BCD, as the radios store frequencies (in units of 10 Hz). */
internal object Bcd {
    /** Null when a nibble is not a decimal digit (an erased field reads 0xFF). */
    fun decode(b: ByteArray, off: Int, len: Int = 4): Long? {
        var v = 0L
        for (i in len - 1 downTo 0) {
            val hi = (b[off + i].toInt() shr 4) and 0xF
            val lo = b[off + i].toInt() and 0xF
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }

    fun encode(value: Long, b: ByteArray, off: Int, len: Int = 4) {
        var v = value
        for (i in 0 until len) {
            val pair = (v % 100).toInt()
            b[off + i] = (((pair / 10) shl 4) or (pair % 10)).toByte()
            v /= 100
        }
    }
}

internal fun ByteArray.u16(off: Int) = (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.putU16(off: Int, v: Int) {
    this[off] = v.toByte()
    this[off + 1] = (v shr 8).toByte()
}

/** The radio prints names in plain ASCII; an erased byte (0xFF) is a blank. */
internal fun ByteArray.asciiName(off: Int, len: Int): String =
    String(CharArray(len) { i ->
        val c = this[off + i].toInt() and 0xFF
        if (c in 32..126) c.toChar() else if (c == 0xFF) ' ' else '\u0000'
    }).replace("\u0000", "").trimEnd()

internal fun ByteArray.putAsciiName(off: Int, len: Int, name: String) {
    val n = name.uppercase().filter { it.code in 32..126 }
    for (i in 0 until len) this[off + i] = if (i < n.length) n[i].code.toByte() else 0xFF.toByte()
}
