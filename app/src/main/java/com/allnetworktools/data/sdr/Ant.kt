package com.allnetworktools.data.sdr

import java.util.Locale

/**
 * ANT / ANT+ over the air, ported from OnAir (core/src/ant, GPL-3.0). The air format is Nordic ShockBurst as sniffed
 * with SDRs (not published by Garmin; two independent reverse-engineering efforts agree): 1 Mbit/s GFSK, about 160 kHz
 * deviation, bytes most significant bit first: preamble 0xAA or 0x55, network marker (0xA6C5 for ANT+), device number
 * (low byte first), device type, transmission type, length 0x0A, 8 payload bytes, CRC-16/CCITT-FALSE over the marker
 * through the payload. ANT+ sensors sit on 2457 MHz.
 */
object Ant {
    const val CHANNEL_HZ = 2_457_000_000L
    const val ANT_PLUS = 0xA6C5
    const val FRAME_BYTES = 17

    fun crc16(p: ByteArray, n: Int): Int {
        var c = 0xFFFF
        for (i in 0 until n) {
            c = c xor ((p[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    class Frame(val marker: Int, val deviceNumber: Int, val deviceType: Int, val transType: Int, val payload: ByteArray) {
        val type get() = deviceType and 0x7F
        val pairing get() = deviceType and 0x80 != 0
        val antPlus get() = marker == ANT_PLUS
    }

    /** The 17 bytes after the preamble; null unless the length is 0x0A and the CRC matches. */
    fun parse(b: ByteArray): Frame? {
        if (b.size < FRAME_BYTES || (b[6].toInt() and 0xFF) != 0x0A) return null
        if (crc16(b, 15) != (((b[15].toInt() and 0xFF) shl 8) or (b[16].toInt() and 0xFF))) return null
        return Frame(((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF), (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8), b[4].toInt() and 0xFF, b[5].toInt() and 0xFF, b.copyOfRange(7, 15))
    }

    /** The air bits of a frame, preamble included (tests). */
    fun bits(marker: Int, number: Int, type: Int, trans: Int, payload: ByteArray): IntArray {
        val b = ByteArray(FRAME_BYTES)
        b[0] = (marker shr 8).toByte(); b[1] = marker.toByte(); b[2] = number.toByte(); b[3] = (number shr 8).toByte()
        b[4] = type.toByte(); b[5] = trans.toByte(); b[6] = 0x0A
        payload.copyInto(b, 7, 0, 8)
        val c = crc16(b, 15); b[15] = (c shr 8).toByte(); b[16] = c.toByte()
        val pre = if (b[0].toInt() and 0x80 != 0) 0xAA else 0x55
        return (listOf(pre) + b.map { it.toInt() and 0xFF }).flatMap { v -> (7 downTo 0).map { (v shr it) and 1 } }.toIntArray()
    }

    const val HEART_RATE = 120
    const val BIKE_SPEED_CADENCE = 121
    const val BIKE_CADENCE = 122
    const val BIKE_SPEED = 123
    const val STRIDE = 124
    const val BIKE_POWER = 11
    const val ENVIRONMENT = 25

    fun typeName(t: Int) = when (t) {
        HEART_RATE -> "Heart rate monitor"; BIKE_SPEED_CADENCE -> "Bike speed and cadence"; BIKE_CADENCE -> "Bike cadence"; BIKE_SPEED -> "Bike speed"
        STRIDE -> "Footpod (stride)"; BIKE_POWER -> "Bike power meter"; 17 -> "Fitness equipment"; 15 -> "Multi-sport speed and distance"
        119 -> "Weight scale"; 16 -> "Remote control"; ENVIRONMENT -> "Environment sensor"; 20 -> "Light electric vehicle"; 18 -> "Blood pressure monitor"
        19 -> "Geocache"; 31 -> "Muscle oxygen monitor"; 40 -> "Bike radar"; 35 -> "Bike lights"; 34 -> "Shifting"; else -> "ANT device"
    }

    private val makers = (
        "1 Garmin;2 Garmin (FR405);3 Zephyr;4 Dayton;5 IDT;6 SRM;7 Quarq;8 iBike;9 Saris;10 Spark HK;11 Tanita;12 Echowell;13 Dynastream OEM;" +
            "14 Nautilus;15 Dynastream;16 Timex;17 Metrigear;18 Xelic;19 Beurer;20 Cardiosport;21 A&D;22 HMM;23 Suunto;24 Thita Elektronik;25 G.Pulse;" +
            "26 Clean Mobile;27 Pedal Brain;28 Peaksware;29 Saxonar;30 Lemond Fitness;31 Dexcom;32 Wahoo Fitness;33 Octane Fitness;34 Archinoetics;" +
            "35 The Hurt Box;36 Citizen Systems;37 Magellan;38 Osynce;39 Holux;40 Concept2;42 One Giant Leap;43 Ace Sensor;44 Brim Brothers;45 Xplova;" +
            "46 Perception Digital;47 BF1 Systems;48 Pioneer;49 Spantec;50 Metalogics;51 4iiii;52 Seiko Epson;53 Seiko Epson OEM;54 IFOR Powell;" +
            "55 Maxwell Guider;56 StarTrac;57 Breakaway;58 Alatech;59 MIO Technology;60 Rotor;61 Geonaute;62 ID Bike;63 Specialized;64 Wtek;" +
            "65 Physical Enterprises;66 North Pole Engineering;67 BKool;68 Cateye;69 Stages Cycling;70 Sigma Sport;71 TomTom;72 Peripedal;73 Wattbike;" +
            "76 Moxy;77 CicloSport;78 Powerbahn;79 Acorn Projects;80 Lifebeam;81 Bontrager;82 Wellgo;83 Scosche;84 Magura;85 Woodway;86 Elite;" +
            "87 Nielsen-Kellerman;88 DK City;89 Tacx;90 Direction Technology;91 Magtonic;92 1partcarbon;93 Inside Ride;94 Sound of Motion;95 Stryd;96 ICG;" +
            "97 DK City;98 Giant;99 Look;100 Polar;101 Ritchey;102 Schwinn;103 LiveRider;104 Grantham;105 Sigma Sport;106 Favero;107 Trainingpeaks;108 Bryton;" +
            "109 SRAM;110 Navman;111 COBI;112 Spivi;113 Mio Magellan;114 Mio Magellan;115 Coros;116 Shimano;117 Mio Technology;118 Zwift;119 JetBlack;" +
            "120 Lezyne;121 Saris;122 iFit;123 Johnson Health Tech;124 Keiser;125 Precor;126 Sole Fitness;127 Zwift;255 development"
        ).split(';').associate { it.substringBefore(' ').toInt() to it.substringAfter(' ') }

    fun maker(id: Int) = makers[id]

    private fun battery(s: Int) = when (s) { 1 -> "new"; 2 -> "good"; 3 -> "ok"; 4 -> "low"; 5 -> "critical"; else -> "unknown" }

    /** What a payload says, in the profile of its device type. */
    class Reading(val pageName: String, val text: String, val heartRate: Int? = null, val powerW: Int? = null, val cadenceRpm: Int? = null, val temperatureC: Double? = null, val maker: String? = null)

    private fun u16(p: ByteArray, o: Int) = (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8)
    private fun f(fmt: String, vararg a: Any?) = String.format(Locale.ROOT, fmt, *a)

    private fun common(page: Int, p: ByteArray): Reading? {
        fun u(i: Int) = p[i].toInt() and 0xFF
        return when (page) {
            80 -> { val m = u16(p, 4); val name = maker(m); Reading("Manufacturer", "${name ?: "manufacturer $m"}, model ${u16(p, 6)}, hardware rev ${u(3)}", maker = name) }
            81 -> Reading("Product", "software rev ${u(3)}, serial ${(u(4).toLong() or (u(5).toLong() shl 8) or (u(6).toLong() shl 16) or (u(7).toLong() shl 24))}")
            82 -> {
                val v = if (u(3) and 0x0F == 0x0F) null else (u(3) and 0x0F) + u(2) / 256.0
                Reading("Battery", "battery ${battery((u(3) shr 4) and 7)}" + (v?.let { f(", %.2f V", it) } ?: ""))
            }
            else -> null
        }
    }

    fun decode(type: Int, p: ByteArray): Reading {
        fun u(i: Int) = p[i].toInt() and 0xFF
        val page = u(0) and 0x7F
        when (type) {
            HEART_RATE -> {
                val beatTime = u16(p, 4) / 1024.0; val count = u(6); val hr = u(7)
                var name = "Page $page"; var extra = ""; var maker: String? = null
                when (page) {
                    0 -> name = "Heart rate"
                    1 -> { name = "Operating time"; extra = f(", on for %.1f h", (u(1) or (u(2) shl 8) or (u(3) shl 16)) * 2 / 3600.0) }
                    2 -> { name = "Manufacturer"; maker = maker(u(1)); extra = ", ${maker ?: "manufacturer ${u(1)}"}, serial ${u16(p, 2)}" }
                    3 -> { name = "Product"; extra = ", hw ${u(1)} sw ${u(2)} model ${u(3)}" }
                    4 -> { name = "Previous beat"; var rr = beatTime - u16(p, 2) / 1024.0; if (rr < 0) rr += 64; extra = f(", R-R %.0f ms", rr * 1000) }
                    5 -> { name = "Swim interval"; extra = ", interval average ${u(1)} bpm, max ${u(2)} bpm" }
                    6 -> { name = "Capabilities"; extra = f(", features 0x%02X enabled 0x%02X", u(2), u(3)) }
                    7 -> { name = "Battery"; extra = if (u(1) != 0xFF) ", battery ${u(1)} % (${battery((u(3) shr 4) and 7)})" else ", battery ${battery((u(3) shr 4) and 7)}" }
                }
                val text = if (hr > 0) f("%d bpm, beat %d at %.3f s", hr, count, beatTime) + extra else "no heart rate (sensor not on the skin?), beat $count$extra"
                return Reading(name, text, heartRate = hr.takeIf { it > 0 }, maker = maker)
            }
            BIKE_SPEED_CADENCE -> return Reading("Speed and cadence", f("cadence %d revs at %.3f s, wheel %d revs at %.3f s", u16(p, 2), u16(p, 0) / 1024.0, u16(p, 6), u16(p, 4) / 1024.0))
            BIKE_CADENCE, BIKE_SPEED -> {
                val extra = when (page) {
                    1 -> f(", on for %.1f h", (u(1) or (u(2) shl 8) or (u(3) shl 16)) * 2 / 3600.0)
                    2 -> ", ${maker(u(1)) ?: "manufacturer"} serial ${u16(p, 2)}"
                    3 -> ", hw ${u(1)} sw ${u(2)} model ${u(3)}"
                    else -> ""
                }
                return Reading(if (type == BIKE_CADENCE) "Cadence" else "Speed", f("%d revs at %.3f s", u16(p, 6), u16(p, 4) / 1024.0) + extra)
            }
            BIKE_POWER -> {
                when (page) {
                    0x10 -> {
                        val cad = if (u(3) == 0xFF) null else u(3)
                        val pct = if (u(2) == 0xFF) null else u(2) and 0x7F
                        val w = u16(p, 6)
                        return Reading("Power", "$w W" + (cad?.let { ", $it rpm" } ?: "") + (pct?.let { ", $it % " + if (u(2) and 0x80 != 0) "right" else "pedal" } ?: ""), powerW = w, cadenceRpm = cad)
                    }
                    0x12 -> return Reading("Crank torque", "crank torque page, ${if (u(3) == 0xFF) "-" else u(3)} rpm")
                    0x11 -> return Reading("Wheel torque", "wheel torque page, ${if (u(3) == 0xFF) "-" else u(3)} rpm")
                    0x01 -> return Reading("Calibration", f("calibration id 0x%02X", u(1)))
                }
            }
            STRIDE -> when (page) {
                1 -> { val d = u(3) + (u(4) shr 4) / 16.0; val v = (u(4) and 0x0F) + u(5) / 256.0; return Reading("Speed and distance", f("%.2f m/s (%.1f km/h), distance %.2f m, strides %d", v, v * 3.6, d, u(6))) }
                2 -> { val v = (u(4) and 0x0F) + u(5) / 256.0; return Reading("Cadence", f("%d strides a minute, %.2f m/s", u(3), v)) }
            }
            ENVIRONMENT -> if (page == 1) {
                val t = (u16(p, 6).toShort()) / 100.0
                val lo = u(3) or ((u(4) and 0x0F) shl 8); val hi = (u(4) shr 4) or (u(5) shl 4)
                fun s12(v: Int) = (if (v and 0x800 != 0) v - 0x1000 else v) / 10.0
                return Reading("Temperature", f("%.2f °C (24 h low %.1f, high %.1f)", t, s12(lo), s12(hi)), temperatureC = t)
            }
        }
        return common(page, p) ?: Reading("Page $page", "page $page: " + p.copyOfRange(1, 8).joinToString(" ") { "%02X".format(it) })
    }
}

/** A frame received with a good CRC. */
class AntHeard(val frame: Ant.Frame, val snrDb: Double)

/**
 * 1 Mbit/s GFSK at 4 MS/s (four samples a bit), the channel centred: the phase turn between samples, summed over a bit
 * and compared with its mean over the 24 bits of preamble and marker (the carrier offset), gives the bit; the four sample phases and both
 * orientations each look for the preamble (two errors allowed) and the ANT+ marker (one), then read the 15 bytes that follow.
 */
class AntReceiver(private val onFrame: (AntHeard) -> Unit) {
    private val sps = 4
    private val sync = 0xAAA6C5
    private val sync2 = 0x55A6C5 and 0xFFFFFF
    private var pr = 0f
    private var pi = 0f
    private val dring = FloatArray(sps)
    private var dsum = 0f
    // The running mean of the phase turn over 24 bits, weighted by power: the silence before a burst (noise, no carrier
    // offset) hardly counts, so the threshold is right from the first bits of the preamble.
    private val meanIm = FloatArray(24 * sps)
    private val meanP = FloatArray(24 * sps)
    private var meanPos = 0
    private var sumIm = 0.0
    private var sumP = 0.0
    private var k = 0L
    private var dcR = 0.0
    private var dcI = 0.0
    private var sig = 0.0
    private var noise = 1e-6
    // Channel filter: the GFSK main lobe (about ±700 kHz) passes, the rest of the 4 MHz of noise does not reach the discriminator.
    private val nt = 15
    private val taps = FloatArray(nt).also { t ->
        val fc = 0.24
        var sum = 0.0
        val v = DoubleArray(nt) { i -> val m = i - (nt - 1) / 2.0; (if (m == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * m) / (Math.PI * m)) * (0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (nt - 1))) }
        sum = v.sum()
        for (i in 0 until nt) t[i] = (v[i] / sum).toFloat()
    }
    private val hr = FloatArray(2 * nt)
    private val hi = FloatArray(2 * nt)
    private var hp = 0

    private inner class Stream(val inv: Boolean) {
        var reg = 0
        var collecting = -1
        val bytes = ByteArray(Ant.FRAME_BYTES)
        var bitsIn = 0
        private var frozen = 0f

        /** [sum]: the phase turn over the last bit; [bias]: its running mean, frozen for the frame once the marker is found. */
        fun push(sum: Float, bias: Float) {
            val b0 = if (sum - (if (collecting >= 0) frozen else bias) > 0) 1 else 0
            val b = if (inv) b0 xor 1 else b0
            if (collecting >= 0) {
                val i = bitsIn / 8
                bytes[i] = ((bytes[i].toInt() shl 1) or b).toByte()
                bitsIn++
                if (bitsIn == Ant.FRAME_BYTES * 8) {
                    collecting = -1
                    Ant.parse(bytes)?.let { emit(it) }
                }
                return
            }
            reg = ((reg shl 1) or b) and 0xFFFFFF
            // The marker must be nearly exact; the preamble, the first bits of the burst, is often damaged by the transmitter
            // ramping up and is allowed two errors.
            val marker = Integer.bitCount((reg xor sync) and 0xFFFF)
            val pre = minOf(Integer.bitCount((reg xor sync) ushr 16), Integer.bitCount((reg xor sync2) ushr 16))
            if (marker <= 1 && pre <= 2) {
                bytes[0] = (Ant.ANT_PLUS shr 8).toByte(); bytes[1] = Ant.ANT_PLUS.toByte()
                for (i in 2 until bytes.size) bytes[i] = 0
                // Preamble and marker hold as many ones as zeros: the mean over them is the carrier offset; the payload's long
                // runs would drag a running mean away.
                frozen = bias
                bitsIn = 16
                collecting = 0
            }
        }
    }

    private val streams = Array(sps) { Array(2) { Stream(it == 1) } }
    private val recent = ArrayDeque<Pair<Long, Int>>()

    private fun emit(f: Ant.Frame) {
        val h = 31 * f.deviceNumber + f.payload.contentHashCode()
        while (recent.isNotEmpty() && k - recent.first().first > 4_000) recent.removeFirst()
        if (recent.any { it.second == h }) return
        recent.addLast(k to h)
        onFrame(AntHeard(f, 10 * kotlin.math.log10(maxOf(1e-3, sig / noise))))
    }

    /** Complex samples at 4 MS/s with the channel at zero. */
    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            // Quick at first (an average of what came so far), then slow.
            val a = if (k < 10_000) 1.0 / (k + 1) else 1e-4
            dcR += a * (re[i] - dcR); dcI += a * (im[i] - dcI)
            val ur = (re[i] - dcR).toFloat(); val ui = (im[i] - dcI).toFloat()
            hr[hp] = ur; hr[hp + nt] = ur; hi[hp] = ui; hi[hp + nt] = ui
            hp = (hp + 1) % nt
            var xr = 0f; var xi = 0f
            for (j in 0 until nt) { xr += taps[j] * hr[hp + j]; xi += taps[j] * hi[hp + j] }
            val p = xr * xr + xi * xi
            // sin of the phase turn; bounded, so the silence between bursts (no amplitude) cannot swamp the running mean.
            val d = ((xi * pr - xr * pi) / (p + 1e-9f)).coerceIn(-1f, 1f)
            val pr0 = pr; val pi0 = pi
            pr = xr; pi = xi
            sig += 0.001 * (p - sig)
            if (p < 3 * noise || noise < 1e-5) noise += 0.0002 * (p - noise)
            val slot = (k % sps).toInt()
            dsum += d - dring[slot]; dring[slot] = d
            val w = xi * pr0 - xr * pi0
            sumIm += w - meanIm[meanPos]; meanIm[meanPos] = w
            sumP += p - meanP[meanPos]; meanP[meanPos] = p
            meanPos = (meanPos + 1) % meanIm.size
            val bias = (sumIm / maxOf(sumP, 1e-9) * sps).toFloat()
            for (s in streams[slot]) s.push(dsum, bias)
            k++
        }
    }

    fun feedBytes(buf: ByteArray, len: Int, re: FloatArray, im: FloatArray) {
        var off = 0
        while (off < len) {
            val m = minOf(re.size * 2, len - off) / 2
            for (i in 0 until m) { re[i] = buf[off + 2 * i].toFloat(); im[i] = buf[off + 2 * i + 1].toFloat() }
            feed(re, im, m)
            off += 2 * m
        }
    }

    companion object {
        const val SAMPLE_RATE = 4_000_000
    }
}
