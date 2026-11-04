package com.allnetworktools.data.sdr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Z-Wave (ITU-T G.9959 MAC), ported from OnAir (core/src/zwave, GPL-3.0): FSK at R1 9.6 kbit/s Manchester, R2 40 kbit/s
 * NRZ (868.40 / 868.42 MHz in Europe) and R3 100 kbit/s (869.85 MHz). The frame header is in the clear: home id (the
 * network), source and destination nodes, frame control, length, then the command class and command; S0 and S2
 * payloads are encrypted and only named. A checksum (R1, R2) or a CRC-16 (R3) closes the frame.
 */
object Zwave {
    fun chipRate(rate: Int) = when (rate) { 1 -> 19_200; 2 -> 40_000; else -> 100_000 }
    fun rateName(rate: Int) = when (rate) { 1 -> "R1 9.6k"; 2 -> "R2 40k"; else -> "R3 100k" }
    fun sof(rate: Int) = if (rate == 3) 0x5B else 0xF0
    fun maxLength(rate: Int) = if (rate == 3) 170 else 64
    fun checkOctets(rate: Int) = if (rate == 3) 2 else 1

    fun checksum8(d: ByteArray, n: Int): Int { var c = 0xFF; for (i in 0 until n) c = c xor (d[i].toInt() and 0xFF); return c }

    fun crc16(d: ByteArray, n: Int): Int {
        var c = 0x1D0F
        for (i in 0 until n) {
            c = c xor ((d[i].toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c
    }

    const val HDR_SINGLECAST = 1
    const val HDR_MULTICAST = 2
    const val HDR_ACK = 3
    const val HDR_ROUTED = 8

    data class Frame(
        val homeId: Long,
        val src: Int,
        /** Destination node (255: everyone); −1 for a multicast. */
        val dst: Int,
        val headerType: Int,
        val routed: Boolean,
        val ackReq: Boolean,
        val lowPower: Boolean,
        val beam: Int,
        val seq: Int,
        val group: List<Int>,
        val payload: ByteArray,
        val length: Int,
        val checkOk: Boolean,
    ) {
        val home get() = "%08X".format(homeId)
    }

    /** MPDU octets with the check at the end; null when too short or the length field disagrees. */
    fun parse(d: ByteArray, rate: Int): Frame? {
        val n = d.size
        val chk = checkOctets(rate)
        if (n < 9 + chk || n > 255) return null
        fun u(i: Int) = d[i].toInt() and 0xFF
        val home = (u(0).toLong() shl 24) or (u(1).toLong() shl 16) or (u(2).toLong() shl 8) or u(3).toLong()
        val fc1 = u(5); val fc2 = u(6)
        if (u(7) != n) return null
        val type = fc1 and 0x0F
        var p: Int
        val group = ArrayList<Int>()
        val dst: Int
        if (type == HDR_MULTICAST) {
            val ctl = u(8); val maskLen = ctl and 0x1F; val off = ctl shr 5
            if (maskLen < 1 || maskLen > 29 || 9 + maskLen + chk > n) return null
            for (i in 0 until maskLen) for (b in 0 until 8) if (u(9 + i) and (1 shl b) != 0) group += off * 32 + i * 8 + b + 1
            dst = -1; p = 9 + maskLen
        } else { dst = u(8); p = 9 }
        val ok = if (chk == 1) checksum8(d, n - 1) == u(n - 1) else crc16(d, n - 2) == ((u(n - 2) shl 8) or u(n - 1))
        return Frame(home, u(4), dst, type, fc1 and 0x80 != 0, fc1 and 0x40 != 0, fc1 and 0x20 != 0, (fc2 shr 5) and 3, fc2 and 0x0F, group, d.copyOfRange(p, n - chk), n, ok)
    }

    /** The MPDU of a singlecast frame with its length and check (tests). */
    fun build(home: Long, src: Int, dst: Int, payload: ByteArray, rate: Int, seq: Int = 1, ackReq: Boolean = false): ByteArray {
        val o = ArrayList<Byte>()
        for (s in intArrayOf(24, 16, 8, 0)) o += (home shr s).toByte()
        o += src.toByte(); o += ((HDR_SINGLECAST) or (if (ackReq) 0x40 else 0)).toByte(); o += (seq and 0x0F).toByte(); o += 0; o += dst.toByte()
        payload.forEach { o += it }
        val chk = checkOctets(rate)
        o[7] = (o.size + chk).toByte()
        val b = o.toByteArray()
        return if (chk == 1) b + checksum8(b, b.size).toByte() else crc16(b, b.size).let { c -> b + byteArrayOf((c shr 8).toByte(), c.toByte()) }
    }

    private val ccNames = mapOf(
        0x00 to "NOP", 0x01 to "PROTOCOL", 0x20 to "BASIC", 0x21 to "CONTROLLER_REPLICATION", 0x22 to "APPLICATION_STATUS", 0x23 to "ZIP",
        0x25 to "SWITCH_BINARY", 0x26 to "SWITCH_MULTILEVEL", 0x27 to "SWITCH_ALL", 0x2B to "SCENE_ACTIVATION", 0x2C to "SCENE_ACTUATOR_CONF",
        0x2D to "SCENE_CONTROLLER_CONF", 0x30 to "SENSOR_BINARY", 0x31 to "SENSOR_MULTILEVEL", 0x32 to "METER", 0x33 to "SWITCH_COLOR",
        0x34 to "NETWORK_MANAGEMENT_INCLUSION", 0x35 to "METER_PULSE", 0x38 to "THERMOSTAT_HEATING", 0x40 to "THERMOSTAT_MODE",
        0x42 to "THERMOSTAT_OPERATING_STATE", 0x43 to "THERMOSTAT_SETPOINT", 0x44 to "THERMOSTAT_FAN_MODE", 0x45 to "THERMOSTAT_FAN_STATE",
        0x46 to "CLIMATE_CONTROL_SCHEDULE", 0x47 to "THERMOSTAT_SETBACK", 0x4C to "DOOR_LOCK_LOGGING", 0x4D to "NETWORK_MANAGEMENT_BASIC",
        0x4E to "SCHEDULE_ENTRY_LOCK", 0x50 to "BASIC_WINDOW_COVERING", 0x52 to "NETWORK_MANAGEMENT_PROXY", 0x53 to "SCHEDULE",
        0x54 to "NETWORK_MANAGEMENT_PRIMARY", 0x55 to "TRANSPORT_SERVICE", 0x56 to "CRC_16_ENCAP", 0x57 to "APPLICATION_CAPABILITY",
        0x59 to "ASSOCIATION_GRP_INFO", 0x5A to "DEVICE_RESET_LOCALLY", 0x5B to "CENTRAL_SCENE", 0x5D to "ANTITHEFT", 0x5E to "ZWAVEPLUS_INFO",
        0x60 to "MULTI_CHANNEL", 0x62 to "DOOR_LOCK", 0x63 to "USER_CODE", 0x66 to "BARRIER_OPERATOR", 0x67 to "NETWORK_MANAGEMENT_INSTALLATION_MAINTENANCE",
        0x6A to "WINDOW_COVERING", 0x6B to "IRRIGATION", 0x6C to "SUPERVISION", 0x6F to "ENTRY_CONTROL", 0x70 to "CONFIGURATION", 0x71 to "NOTIFICATION",
        0x72 to "MANUFACTURER_SPECIFIC", 0x73 to "POWERLEVEL", 0x74 to "INCLUSION_CONTROLLER", 0x75 to "PROTECTION", 0x76 to "LOCK", 0x77 to "NODE_NAMING",
        0x78 to "NODE_PROVISIONING", 0x79 to "SOUND_SWITCH", 0x7A to "FIRMWARE_UPDATE_MD", 0x7B to "GROUPING_NAME", 0x80 to "BATTERY", 0x81 to "CLOCK",
        0x82 to "HAIL", 0x84 to "WAKE_UP", 0x85 to "ASSOCIATION", 0x86 to "VERSION", 0x87 to "INDICATOR", 0x88 to "PROPRIETARY", 0x89 to "LANGUAGE",
        0x8A to "TIME", 0x8B to "TIME_PARAMETERS", 0x8C to "GEOGRAPHIC_LOCATION", 0x8E to "MULTI_CHANNEL_ASSOCIATION", 0x8F to "MULTI_CMD",
        0x90 to "ENERGY_PRODUCTION", 0x91 to "MANUFACTURER_PROPRIETARY", 0x98 to "SECURITY", 0x9C to "SENSOR_ALARM", 0x9D to "SILENCE_ALARM",
        0x9F to "SECURITY_2",
    )

    fun ccName(cc: Int) = ccNames[cc]

    private fun cmdName(cc: Int, cmd: Int): String? = when (cc) {
        0x01 -> when (cmd) {
            0x01 -> "Node Info"; 0x02 -> "Request Node Info"; 0x03 -> "Assign IDs"; 0x04 -> "Find Nodes In Range"; 0x05 -> "Get Nodes In Range"
            0x06 -> "Range Info"; 0x07 -> "Command Complete"; 0x0C -> "Assign Return Route"; 0x0D -> "New Node Registered"; 0x11 -> "SUC Node ID"
            0x12 -> "Set SUC"; 0x15 -> "Static Route Request"; 0x16 -> "Lost"; 0x18 -> "NOP Power"; else -> null
        }
        0x20, 0x25 -> when (cmd) { 1 -> "Set"; 2 -> "Get"; 3 -> "Report"; else -> null }
        0x26 -> when (cmd) { 1 -> "Set"; 2 -> "Get"; 3 -> "Report"; 4 -> "Start Level Change"; 5 -> "Stop Level Change"; else -> null }
        0x30 -> when (cmd) { 2 -> "Get"; 3 -> "Report"; else -> null }
        0x31, 0x71, 0x72 -> when (cmd) { 4 -> "Get"; 5 -> "Report"; else -> null }
        0x32 -> when (cmd) { 1 -> "Get"; 2 -> "Report"; else -> null }
        0x62 -> when (cmd) { 1 -> "Operation Set"; 2 -> "Operation Get"; 3 -> "Operation Report"; else -> null }
        0x70 -> when (cmd) { 4 -> "Set"; 5 -> "Get"; 6 -> "Report"; else -> null }
        0x5E, 0x6C -> when (cmd) { 1 -> "Get"; 2 -> "Report"; else -> null }
        0x80 -> when (cmd) { 2 -> "Get"; 3 -> "Report"; else -> null }
        0x84 -> when (cmd) { 4 -> "Interval Set"; 5 -> "Interval Get"; 6 -> "Interval Report"; 7 -> "Notification"; 8 -> "No More Information"; else -> null }
        0x85 -> when (cmd) { 1 -> "Set"; 2 -> "Get"; 3 -> "Report"; 4 -> "Remove"; else -> null }
        0x86 -> when (cmd) { 0x11 -> "Get"; 0x12 -> "Report"; else -> null }
        else -> null
    }

    /** 0 in the clear, 1 S0 encapsulation, 2 S2 encapsulation. */
    fun encryption(p: ByteArray): Int {
        if (p.size < 2) return 0
        val a = p[0].toInt() and 0xFF; val b = p[1].toInt() and 0xFF
        return if (a == 0x98 && (b == 0x81 || b == 0xC1)) 1 else if (a == 0x9F && b == 0x03) 2 else 0
    }

    private fun sensorType(t: Int) = when (t) {
        1 -> "temperature"; 2 -> "general"; 3 -> "luminance"; 4 -> "power"; 5 -> "humidity"; 6 -> "velocity"; 7 -> "direction"
        8 -> "atmospheric pressure"; 9 -> "barometric pressure"; 10 -> "solar radiation"; else -> "sensor"
    }

    /** One line for the payload: "SWITCH_BINARY Set on", "Security S2 encrypted (n octets)". */
    fun describe(f: Frame): String {
        if (f.headerType == HDR_ACK) return "ack"
        val p = f.payload
        if (p.isEmpty()) return if (f.routed) "routed" else ""
        if (f.routed) return "routed"
        val enc = encryption(p)
        if (enc != 0) return "Security ${if (enc == 1) "S0" else "S2"} encrypted (${p.size} octets)"
        fun u(i: Int) = p[i].toInt() and 0xFF
        val cc = u(0)
        if (cc == 0) return "NOP"
        var s = ccName(cc) ?: "command class 0x%02X".format(cc)
        if (p.size < 2) return s
        s += cmdName(cc, u(1))?.let { " $it" } ?: " cmd 0x%02X".format(u(1))
        if (p.size >= 3 && (cc == 0x20 || cc == 0x25 || cc == 0x26) && (u(1) == 1 || u(1) == 3)) {
            val v = u(2)
            s += when { v == 0 -> " off"; v == 0xFF -> " on"; v <= 99 -> " $v %"; else -> "" }
        } else if (p.size >= 3 && cc == 0x80 && u(1) == 3) {
            s += if (u(2) == 0xFF) " low battery" else " ${u(2)} %"
        } else if (p.size >= 3 && cc == 0x30 && u(1) == 3) {
            s += if (u(2) != 0) " triggered" else " idle"
        } else if (p.size >= 5 && cc == 0x31 && u(1) == 5) {
            val size = u(3) and 7; val prec = u(3) shr 5; val scale = (u(3) shr 3) and 3
            if ((size == 1 || size == 2 || size == 4) && p.size >= 4 + size) {
                var v = p[4].toInt()
                for (i in 1 until size) v = (v shl 8) or u(4 + i)
                var x = v.toDouble()
                repeat(prec) { x /= 10 }
                s += " ${sensorType(u(2))} " + "%.${prec}f".format(java.util.Locale.ROOT, x) + if (u(2) == 1) (if (scale != 0) " °F" else " °C") else ""
            }
        }
        return s
    }

    /** The European channels: R1 on 868.42 MHz, R2 on 868.40 MHz (one channel filter for both), R3 on 869.85 MHz. */
    val EU_CENTRE_HZ = 869_130_000L
    val EU_GROUPS = listOf(868_410_000L to listOf(1, 2), 869_850_000L to listOf(3))
}

/** A frame received, with where and how strong. */
class ZwaveHeard(val frame: Zwave.Frame, val rate: Int, val hz: Long, val snrDb: Double)

/**
 * The European channels from one tuning at 2 MS/s centred on 869.13 MHz: each channel group is mixed to zero, filtered
 * (110 kHz) and decimated to 400 kS/s; a power detector cuts bursts, which are FM-demodulated, cleared of their frequency
 * offset (from the preamble), sliced at each rate's chip rate (Manchester for R1, both polarities) and searched for the
 * start-of-frame byte; the first frame whose length and check agree is kept.
 */
class ZwaveReceiver(private val inputRate: Double = 2_000_000.0, private val centreHz: Long = Zwave.EU_CENTRE_HZ, groups: List<Pair<Long, List<Int>>> = Zwave.EU_GROUPS, private val onFrame: (ZwaveHeard) -> Unit) {
    @Volatile var bursts = 0; private set
    @Volatile var bad = 0; private set

    private inner class Group(val hz: Long, val rates: List<Int>) {
        val d = maxOf(1, (inputRate / 400_000.0).roundToInt())
        val chanRate = inputRate / d
        private val n = 8 * d + 1
        private val taps = run {
            val fc = 110_000.0 / inputRate
            val t = DoubleArray(n) { i ->
                val x = i - (n - 1) / 2.0
                val s = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                s * (0.54 - 0.46 * cos(2 * PI * i / (n - 1)))
            }
            val sum = t.sum()
            FloatArray(n) { (t[it] / sum).toFloat() }
        }
        private val step = -2 * PI * (hz - centreHz) / inputRate
        private val sr = cos(step)
        private val si = sin(step)
        private var lr = 1.0
        private var li = 0.0
        private val hr = FloatArray(2 * n)
        private val hi = FloatArray(2 * n)
        private var hp = 0
        private var dcount = 0
        private var pe = 0f
        private var nz = 0f
        private var nzInit = 0
        private var above = 0
        private var below = 0
        private var inBurst = false
        private val histR = FloatArray(512)
        private val histI = FloatArray(512)
        private var hpos = 0
        private var hfill = 0
        private var curR = FloatArray(8192)
        private var curI = FloatArray(8192)
        private var curN = 0
        private var s0 = 0
        private var nzAtStart = 0f

        fun feed(x: FloatArray, y: FloatArray, count: Int) {
            for (i in 0 until count) {
                val mr = (x[i] * lr - y[i] * li).toFloat()
                val mi = (x[i] * li + y[i] * lr).toFloat()
                val nr = lr * sr - li * si; li = lr * si + li * sr; lr = nr
                hr[hp] = mr; hi[hp] = mi; hr[hp + n] = mr; hi[hp + n] = mi
                hp = (hp + 1) % n
                if (++dcount < d) continue
                dcount = 0
                var re = 0f; var im = 0f
                for (k in 0 until n) { re += taps[k] * hr[hp + k]; im += taps[k] * hi[hp + k] }
                sample(re, im)
            }
            val g = 1 / kotlin.math.hypot(lr, li); lr *= g; li *= g
        }

        private fun push(re: Float, im: Float) {
            if (curN == curR.size) { curR = curR.copyOf(curN * 2); curI = curI.copyOf(curN * 2) }
            curR[curN] = re; curI[curN] = im; curN++
        }

        private fun sample(re: Float, im: Float) {
            val p = re * re + im * im
            pe += 0.2f * (p - pe)
            if (nzInit < 2000) nz += (pe - nz) / (++nzInit)
            if (!inBurst) {
                if (pe < 3 * nz) nz += 0.002f * (pe - nz)
                histR[hpos] = re; histI[hpos] = im; hpos = (hpos + 1) % histR.size; hfill = minOf(hfill + 1, histR.size)
                if (nzInit >= 2000 && pe > 6 * nz) above++ else above = 0
                if (above >= 6) {
                    inBurst = true; below = 0; nzAtStart = nz
                    val pre = minOf(hfill, 206)
                    curN = 0
                    for (j in 0 until pre) { val k = (hpos + histR.size - pre + j) % histR.size; push(histR[k], histI[k]) }
                    s0 = if (pre >= 12) pre - 12 else 0
                }
            } else {
                push(re, im)
                if (pe < 2.5f * nzAtStart) below++ else below = 0
                if (below >= 160 || curN > (0.25 * chanRate).toInt()) {
                    inBurst = false; above = 0; hfill = 0
                    done()
                    curN = 0
                }
            }
        }

        private fun done() {
            val fs = chanRate
            val len = curN
            if (len < 400) return
            bursts++
            val dd = FloatArray(len)
            for (i in 1 until len) {
                val ar = curR[i] * curR[i - 1] + curI[i] * curI[i - 1]
                val ai = curI[i] * curR[i - 1] - curR[i] * curI[i - 1]
                dd[i] = (atan2(ai, ar) * fs / (2 * PI)).toFloat()
            }
            val a = s0 + (0.0004 * fs).toInt()
            val e = minOf(len, s0 + (0.0024 * fs).toInt())
            var cfo = 0.0
            if (e > a + 20) { for (i in a until e) cfo += dd[i]; cfo /= (e - a) }
            for (i in dd.indices) dd[i] = (dd[i] - cfo).toFloat()
            var pw = 0.0; var np = 0
            val pb = minOf(len, s0 + (0.0004 * fs).toInt()); val pend = if (len > 200) len - 160 else len
            for (i in pb until pend) { pw += curR[i] * curR[i] + curI[i] * curI[i]; np++ }
            pw = if (np > 0) pw / np else 0.0
            val snr = 10 * log10(maxOf(1e-3, pw / maxOf(1e-20, nzAtStart.toDouble()) - 1))
            var sawSof = false
            for (r in rates) {
                val (f, saw) = decodeRate(dd, a, r, fs)
                if (f != null) { onFrame(ZwaveHeard(f, r, hz, snr)); return }
                if (saw) sawSof = true
            }
            if (sawSof) bad++
        }
    }

    private val groups = groups.map { Group(it.first, it.second) }

    fun feed(re: FloatArray, im: FloatArray, n: Int) { for (g in groups) g.feed(re, im, n) }

    /** 2 MS/s HackRF bytes. */
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
        /** A demodulated burst read at one rate: the frame, and whether a start of frame was seen at all. */
        fun decodeRate(d: FloatArray, a: Int, rate: Int, chanRate: Double): Pair<Zwave.Frame?, Boolean> {
            val spc = chanRate / Zwave.chipRate(rate)
            val w = maxOf(1, spc.roundToInt())
            if (d.size < a + 40 * spc + w) return null to false
            val ps = DoubleArray(d.size + 1)
            for (i in d.indices) ps[i + 1] = ps[i] + d[i]
            fun m(pos: Double): Double {
                val i = pos.roundToInt()
                if (i < w || i >= d.size) return 0.0
                return (ps[i + 1] - ps[i + 1 - w]) / w
            }
            var best = -1.0
            var bphi = 0.0
            var phi = 0.0
            while (phi < spc) {
                var s = 0.0
                for (k in 1..16) s += abs(m(a + phi + k * spc))
                if (s > best) { best = s; bphi = phi }
                phi += 0.5
            }
            val chips = ArrayList<Int>()
            var k = 0
            while (true) {
                val pos = a + bphi + k * spc
                if (pos >= d.size - 1) break
                chips += if (m(pos) > 0) 1 else 0
                k++
            }
            var saw = false
            for (inv in 0..1) {
                if (rate == 1) {
                    for (al in 0..1) {
                        val bits = ArrayList<Int>()
                        var j = al
                        while (j + 1 < chips.size) { bits += if ((chips[j] xor inv) > (chips[j + 1] xor inv)) 1 else 0; j += 2 }
                        val (f, s) = search(bits, rate)
                        if (s) saw = true
                        if (f != null) return f to true
                    }
                } else {
                    val (f, s) = search(chips.map { it xor inv }, rate)
                    if (s) saw = true
                    if (f != null) return f to true
                }
            }
            return null to saw
        }

        private fun search(bits: List<Int>, rate: Int): Pair<Zwave.Frame?, Boolean> {
            val sof = Zwave.sof(rate)
            val chk = Zwave.checkOctets(rate)
            var saw = false
            var p = 12
            while (p + 8 + 8 * 9 <= bits.size) {
                var v = 0
                for (i in 0 until 8) v = (v shl 1) or bits[p + i]
                if (v == sof) {
                    // An alternating preamble just before the start of frame.
                    var flaws = 0
                    for (i in p - 11 until p) if (bits[i] == bits[i - 1]) flaws++
                    if (flaws <= 1) {
                        val q = p + 8
                        fun byteAt(kk: Int): Int { var b = 0; for (i in 0 until 8) b = (b shl 1) or bits[q + kk * 8 + i]; return b }
                        val len = byteAt(7)
                        if (len >= 9 + chk && len <= Zwave.maxLength(rate) && q + len * 8 <= bits.size) {
                            saw = true
                            val mp = ByteArray(len) { byteAt(it).toByte() }
                            val f = Zwave.parse(mp, rate)
                            if (f != null && f.checkOk) return f to true
                        }
                    }
                }
                p++
            }
            return null to saw
        }
    }
}
