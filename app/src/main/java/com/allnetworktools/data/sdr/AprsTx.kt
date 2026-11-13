package com.allnetworktools.data.sdr

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * APRS beacons as transmitted: an AX.25 UI frame (destination, source, digipeater path, APRS protocol id), its FCS,
 * HDLC flags and bit stuffing, NRZI, then Bell 202 AFSK (1200 Hz mark, 2200 Hz space, 1200 baud) as audio for an FM
 * transmitter.
 */
object AprsTx {
    /** Experimental "tocall" (APZxxx) naming the software in the destination field. */
    const val TOCALL = "APZART"

    /** Frequencies by region: Europe and Africa, the Americas, Australia, China, Japan, New Zealand. */
    val Frequencies = listOf(144_800_000L, 144_390_000L, 145_175_000L, 144_640_000L, 144_660_000L, 144_575_000L)

    /** Symbols offered, as table and code: walking, car, house, bicycle, motorbike, portable station. */
    val Symbols = listOf("/[", "/>", "/-", "/b", "/<", "/;")

    /** One address field: six characters shifted left one bit, then the SSID byte. */
    fun address(call: String, last: Boolean, command: Boolean = false): ByteArray {
        val parts = call.uppercase().split('-')
        val name = parts[0].take(6).padEnd(6, ' ')
        val ssid = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 15) ?: 0
        val out = ByteArray(7)
        for (i in 0 until 6) out[i] = (name[i].code shl 1).toByte()
        out[6] = (0x60 or (ssid shl 1) or (if (last) 1 else 0) or (if (command) 0x80 else 0)).toByte()
        return out
    }

    /** The frame bytes, without FCS. */
    fun frame(src: String, info: String, path: List<String> = listOf("WIDE1-1", "WIDE2-1"), dst: String = TOCALL): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(address(dst, last = false, command = true))
        b.write(address(src, last = path.isEmpty()))
        path.forEachIndexed { i, p -> b.write(address(p, last = i == path.size - 1)) }
        b.write(0x03); b.write(0xF0)
        b.write(info.toByteArray(Charsets.US_ASCII))
        return b.toByteArray()
    }

    /** CRC-16/X.25, sent low byte first. */
    fun fcs(b: ByteArray): Int {
        var crc = 0xFFFF
        for (x in b) {
            var v = x.toInt() and 0xFF
            repeat(8) { crc = if ((crc xor v) and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1; v = v ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }

    /** The bits on air before NRZI: [preambleFlags] flags, the stuffed frame and FCS, closing flags. */
    fun bits(frame: ByteArray, preambleFlags: Int = 40): IntArray {
        val out = ArrayList<Int>()
        fun flag() { for (i in 0 until 8) out += (0x7E shr i) and 1 }
        repeat(preambleFlags) { flag() }
        val c = fcs(frame)
        var ones = 0
        for (byte in frame + byteArrayOf((c and 0xFF).toByte(), (c ushr 8).toByte())) {
            for (i in 0 until 8) {
                val bit = (byte.toInt() shr i) and 1
                out += bit
                if (bit == 1) { if (++ones == 5) { out += 0; ones = 0 } } else ones = 0
            }
        }
        repeat(3) { flag() }
        return out.toIntArray()
    }

    /** AFSK audio at [rate]: NRZI (a 0 changes the tone), continuous phase, [level] peak. */
    fun audio(bits: IntArray, rate: Int, level: Float = 0.5f): FloatArray {
        val perBit = rate / 1200.0
        val out = FloatArray((bits.size * perBit).toInt() + 1)
        var mark = true
        var phase = 0.0
        var t = 0.0
        var k = 0
        for (b in bits) {
            if (b == 0) mark = !mark
            val inc = 2 * PI * (if (mark) 1200.0 else 2200.0) / rate
            t += perBit
            while (k < t && k < out.size) { out[k++] = (level * sin(phase)).toFloat(); phase += inc }
        }
        return out.copyOf(k)
    }

    /** "4852.35N" style: degrees and minutes with two decimals. */
    fun latitude(lat: Double): String {
        val a = abs(lat)
        val d = a.toInt()
        return String.format(Locale.US, "%02d%05.2f%s", d, (a - d) * 60, if (lat >= 0) "N" else "S")
    }

    fun longitude(lon: Double): String {
        val a = abs(lon)
        val d = a.toInt()
        return String.format(Locale.US, "%03d%05.2f%s", d, (a - d) * 60, if (lon >= 0) "E" else "W")
    }

    /** Position report without timestamp: "!", latitude, symbol table, longitude, symbol, then altitude and comment. */
    fun positionInfo(lat: Double, lon: Double, symbol: String, comment: String, altitudeM: Double? = null): String {
        val alt = altitudeM?.let { String.format(Locale.US, "/A=%06d", (it / 0.3048).toInt().coerceIn(0, 999_999)) } ?: ""
        val text = comment.filter { it.code in 0x20..0x7E && it != '|' && it != '~' }.take(40)
        return "!" + latitude(lat) + symbol[0] + longitude(lon) + symbol[1] + alt + (if (text.isNotEmpty()) " $text" else "")
    }

    /** The whole beacon as audio at [rate], ready for an FM transmitter. */
    fun beaconAudio(src: String, info: String, rate: Int): FloatArray {
        // A little silence first so the receiver's squelch opens before the flags.
        val tone = audio(bits(frame(src, info)), rate)
        return FloatArray(rate / 10) + tone + FloatArray(rate / 20)
    }
}
