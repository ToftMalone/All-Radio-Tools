package com.allnetworktools.data.sdr

/**
 * IEEE 802.15.4 / Zigbee frames, receive only, ported from OnAir (core/src/zigbee/zigbee_proto, GPL-3.0-or-later): the MAC header
 * (frame type, security, PAN ID, short and extended addresses, sequence number), MAC commands, the Zigbee beacon payload,
 * the Zigbee NWK header and the APS header where the NWK payload is not encrypted. Encrypted payloads are only named.
 */
object Zigbee {
    fun channelHz(ch: Int) = 2_405_000_000L + 5_000_000L * (ch - 11)

    /** IEEE 802.15.4-2006 table 24: the 32-chip sequence of each 4-bit symbol, chip c0 in the most significant bit. */
    val CHIPS = longArrayOf(
        0xD9C3522EL, 0xED9C3522L, 0x2ED9C352L, 0x22ED9C35L, 0x522ED9C3L, 0x3522ED9CL, 0xC3522ED9L, 0x9C3522EDL,
        0x8C96077BL, 0xB8C96077L, 0x7B8C9607L, 0x77B8C960L, 0x077B8C96L, 0x6077B8C9L, 0x96077B8CL, 0xC96077B8L,
    ).map { it.toInt() }.toIntArray()

    /** The FCS: CRC-16/KERMIT ("123456789" gives 0x2189), sent least significant byte first. */
    fun crc16(d: ByteArray, n: Int): Int {
        var c = 0
        for (i in 0 until n) {
            c = c xor (d[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c and 0xFFFF
    }

    const val BEACON = 0
    const val DATA = 1
    const val ACK = 2
    const val COMMAND = 3

    fun frameTypeName(t: Int) = when (t) { 0 -> "Beacon"; 1 -> "Data"; 2 -> "Ack"; 3 -> "MAC cmd"; 5 -> "Multipurpose"; 6 -> "Fragment"; 7 -> "Extended"; else -> "Reserved" }

    private fun macCommand(c: Int) = when (c) {
        1 -> "Association request"; 2 -> "Association response"; 3 -> "Disassociation notification"; 4 -> "Data request"
        5 -> "PAN ID conflict notification"; 6 -> "Orphan notification"; 7 -> "Beacon request"; 8 -> "Coordinator realignment"; 9 -> "GTS request"
        else -> "Command"
    }

    private fun nwkCommand(c: Int) = when (c) {
        1 -> "Route request"; 2 -> "Route reply"; 3 -> "Network status"; 4 -> "Leave"; 5 -> "Route record"; 6 -> "Rejoin request"
        7 -> "Rejoin response"; 8 -> "Link status"; 9 -> "Network report"; 10 -> "Network update"; 11 -> "End device timeout request"
        12 -> "End device timeout response"; else -> "NWK command"
    }

    fun profileName(p: Int) = when (p) {
        0x0000 -> "ZDO"; 0x0101 -> "Industrial plant monitoring"; 0x0104 -> "Home Automation"; 0x0105 -> "Commercial Building Automation"
        0x0107 -> "Telecom Applications"; 0x0108 -> "Personal Home & Hospital Care"; 0x0109 -> "Smart Energy"; 0xA1E0 -> "Green Power"
        0xC05E -> "ZigBee Light Link"; else -> null
    }

    fun clusterName(c: Int, profile: Int): String? = if (profile == 0) {
        val rsp = c and 0x8000 != 0
        fun rr(n: String) = n + if (rsp) "_rsp" else "_req"
        when (c and 0x7FFF) {
            0x0000 -> rr("NWK_addr"); 0x0001 -> rr("IEEE_addr"); 0x0002 -> rr("Node_Desc"); 0x0003 -> rr("Power_Desc"); 0x0004 -> rr("Simple_Desc")
            0x0005 -> rr("Active_EP"); 0x0006 -> rr("Match_Desc"); 0x0013 -> "Device_annce"; 0x0020 -> rr("End_Device_Bind"); 0x0021 -> rr("Bind")
            0x0022 -> rr("Unbind"); 0x0030 -> rr("Mgmt_NWK_Disc"); 0x0031 -> rr("Mgmt_Lqi"); 0x0032 -> rr("Mgmt_Rtg"); 0x0034 -> rr("Mgmt_Leave")
            0x0036 -> rr("Mgmt_Permit_Joining"); 0x0038 -> rr("Mgmt_NWK_Update"); else -> "ZDO"
        }
    } else when (c) {
        0x0000 -> "Basic"; 0x0001 -> "Power Configuration"; 0x0003 -> "Identify"; 0x0004 -> "Groups"; 0x0005 -> "Scenes"; 0x0006 -> "On/Off"
        0x0008 -> "Level Control"; 0x000A -> "Time"; 0x0019 -> "OTA Upgrade"; 0x0020 -> "Poll Control"; 0x0102 -> "Window Covering"
        0x0201 -> "Thermostat"; 0x0300 -> "Color Control"; 0x0400 -> "Illuminance"; 0x0402 -> "Temperature"; 0x0403 -> "Pressure"
        0x0405 -> "Humidity"; 0x0406 -> "Occupancy"; 0x0500 -> "IAS Zone"; 0x0702 -> "Metering"; 0x0B04 -> "Electrical Measurement"
        0x1000 -> "Touchlink"; else -> null
    }

    private val ouis = mapOf(
        0x00124B to "Texas Instruments", 0x000D6F to "Silicon Labs (Ember)", 0x000B57 to "Silicon Labs", 0x842E14 to "Silicon Labs",
        0x90FD9F to "Silicon Labs", 0x14B457 to "Silicon Labs (IKEA)", 0x680AE2 to "Silicon Labs", 0x60A423 to "Silicon Labs",
        0x8CF681 to "Silicon Labs", 0xEC1BBD to "Silicon Labs", 0xD0CF5E to "Silicon Labs", 0x001788 to "Signify (Philips Hue)",
        0x00158D to "Lumi (Xiaomi Aqara)", 0x04CF8C to "Xiaomi", 0x54EF44 to "Lumi (Xiaomi)", 0x286D97 to "Samsung SmartThings",
        0x24FD5B to "Samsung SmartThings", 0x0013A2 to "Digi (MaxStream)", 0x0004A3 to "Microchip", 0x000781 to "Itron",
        0x001EC0 to "Microchip (Atmel)", 0x00212E to "dresden elektronik", 0x0021ED to "Chipcon (Texas Instruments)",
    )

    fun vendor(ext: Long): String? = ouis[(ext ushr 40).toInt()]

    fun ext(e: Long) = (7 downTo 0).joinToString(":") { "%02X".format((e ushr (8 * it)) and 0xFF) }
    fun short(a: Int) = "0x%04X".format(a)

    class Frame(
        val type: Int, val secured: Boolean, val ackReq: Boolean, val version: Int, val seq: Int,
        val destMode: Int, val srcMode: Int, val destPan: Int?, val srcPan: Int?,
        val destShort: Int, val srcShort: Int, val destExt: Long, val srcExt: Long,
        val encrypted: Boolean, val info: String,
        /** Zigbee NWK addresses, when the frame has that header. */
        val nwkSrc: Int?, val nwkDest: Int?, val nwkSrcExt: Long?,
        /** From a Zigbee beacon. */
        val extPanId: Long?, val permitJoin: Boolean?,
        val length: Int,
    ) {
        val src get() = when (srcMode) { 3 -> ext(srcExt); 2 -> short(srcShort); else -> "-" }
        val dst get() = when (destMode) { 3 -> ext(destExt); 2 -> if (destShort == 0xFFFF) "broadcast" else short(destShort); else -> "-" }
        val pan get() = srcPan ?: destPan
    }

    private fun le16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun le64(d: ByteArray, o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (d[o + i].toLong() and 0xFF); return v }

    /** A PSDU with its FCS (already checked); null when its header cannot be read. */
    fun parse(d: ByteArray): Frame? {
        val total = d.size
        if (total < 5) return null
        val n = total - 2
        val fc = le16(d, 0)
        val type = fc and 7
        val secured = (fc shr 3) and 1 != 0
        val ackReq = (fc shr 5) and 1 != 0
        val comp = (fc shr 6) and 1 != 0
        val seqSup = (fc shr 8) and 1 != 0
        val dm = (fc shr 10) and 3
        val ver = (fc shr 12) and 3
        val sm = (fc shr 14) and 3
        if (dm == 1 || sm == 1 || ver == 3) return null
        var i = 2
        var seq = -1
        if (ver < 2 || !seqSup) { if (n < i + 1) return null; seq = d[i++].toInt() and 0xFF }
        var destPanP = false
        var srcPanP = false
        if (ver < 2) {
            destPanP = dm != 0
            srcPanP = sm != 0 && !comp
        } else {
            val dp = dm != 0; val sp = sm != 0
            when {
                !dp && !sp -> destPanP = comp
                !dp && sp -> srcPanP = !comp
                dp && !sp -> destPanP = !comp
                dm == 3 && sm == 3 -> destPanP = comp
                else -> { destPanP = true; srcPanP = !comp }
            }
        }
        var destPan: Int? = null; var srcPan: Int? = null
        var ds = 0; var ss = 0; var de = 0L; var se = 0L
        if (destPanP) { if (n < i + 2) return null; destPan = le16(d, i); i += 2 }
        if (dm == 2) { if (n < i + 2) return null; ds = le16(d, i); i += 2 } else if (dm == 3) { if (n < i + 8) return null; de = le64(d, i); i += 8 }
        if (srcPanP) { if (n < i + 2) return null; srcPan = le16(d, i); i += 2 } else if (sm != 0 && destPan != null) srcPan = destPan
        if (sm == 2) { if (n < i + 2) return null; ss = le16(d, i); i += 2 } else if (sm == 3) { if (n < i + 8) return null; se = le64(d, i); i += 8 }
        var mic = 0
        var encrypted = false
        if (secured) {
            if (n < i + 1) return null
            val sc = d[i++].toInt() and 0xFF
            val level = sc and 7; val keyMode = (sc shr 3) and 3
            val noCounter = ver >= 2 && (sc shr 5) and 1 != 0
            if (!noCounter) i += 4
            i += when (keyMode) { 0 -> 0; 1 -> 1; 2 -> 5; else -> 9 }
            if (n < i) return null
            mic = if (level and 3 == 0) 0 else 4 shl ((level and 3) - 1)
            encrypted = level >= 4
        }
        if (n < i + mic) return null
        val p = d.copyOfRange(i, n - mic)
        val pl = p.size
        var info = ""
        var nwkSrc: Int? = null; var nwkDest: Int? = null; var nwkSrcExt: Long? = null
        var extPan: Long? = null; var permit: Boolean? = null
        fun frame() = Frame(type, secured, ackReq, ver, seq, dm, sm, destPan, srcPan, ds, ss, de, se, encrypted, info, nwkSrc, nwkDest, nwkSrcExt, extPan, permit, total)
        if (type == ACK) { info = "Ack #$seq"; return frame() }
        if (encrypted) { info = "${frameTypeName(type)}, MAC payload encrypted ($pl bytes)"; return frame() }
        when (type) {
            BEACON -> {
                var k = 0
                var coord = false
                if (pl >= 2) {
                    val sf = le16(p, 0)
                    coord = (sf shr 14) and 1 != 0
                    permit = (sf shr 15) and 1 != 0
                    k = 2
                    if (pl >= k + 1) { val cnt = p[k].toInt() and 7; k += 1; if (cnt != 0) k += 1 + cnt * 3 }
                    if (pl >= k + 1) { val s = p[k].toInt() and 7; val e = (p[k].toInt() shr 4) and 7; k += 1 + s * 2 + e * 8 }
                }
                info = if (k <= pl && pl - k >= 15 && p[k].toInt() == 0) {
                    val stack = p[k + 1].toInt() and 15
                    val router = (p[k + 2].toInt() shr 2) and 1 != 0
                    val endDev = (p[k + 2].toInt() shr 7) and 1 != 0
                    extPan = le64(p, k + 3)
                    "Zigbee beacon: PAN ${short(srcPan ?: 0)}, ext PAN ${ext(extPan!!)}, stack profile $stack" + (if (router) ", router slots" else "") + (if (endDev) ", end-device slots" else "") + ", join " + if (permit == true) "open" else "closed"
                } else "Beacon: PAN ${short(srcPan ?: 0)}" + (if (coord) ", PAN coordinator" else "") + if (permit == true) ", join allowed" else ""
            }
            COMMAND -> {
                val cmd = if (pl >= 1) p[0].toInt() and 0xFF else -1
                info = macCommand(cmd) + when {
                    cmd == 1 && pl >= 2 -> { val c = p[1].toInt(); ": " + (if (c and 2 != 0) "full-function" else "reduced-function") + (if (c and 4 != 0) ", mains powered" else ", battery") + if (c and 8 != 0) ", rx on when idle" else "" }
                    cmd == 2 && pl >= 4 -> { val st = p[3].toInt() and 0xFF; ": address ${short(le16(p, 1))}, " + when (st) { 0 -> "joined"; 1 -> "PAN at capacity"; 2 -> "access denied"; else -> "refused" } }
                    else -> ""
                }
            }
            DATA -> {
                info = "Data, $pl bytes (not Zigbee NWK)"
                if (pl >= 8) {
                    val nfc = le16(p, 0)
                    val proto = (nfc shr 2) and 15
                    val nt = nfc and 3
                    if ((proto == 1 || proto == 2) && nt != 2) {
                        val nsec = (nfc shr 9) and 1 != 0
                        val dExt = (nfc shr 11) and 1 != 0; val sExt = (nfc shr 12) and 1 != 0
                        val mcast = (nfc shr 8) and 1 != 0; val sroute = (nfc shr 10) and 1 != 0
                        nwkDest = le16(p, 2); nwkSrc = le16(p, 4)
                        val radius = p[6].toInt() and 0xFF; val nseq = p[7].toInt() and 0xFF
                        var j = 8
                        var ok = true
                        if (dExt) { if (pl < j + 8) ok = false else j += 8 }
                        if (ok && sExt) { if (pl < j + 8) ok = false else { nwkSrcExt = le64(p, j); j += 8 } }
                        if (ok && mcast) { if (pl < j + 1) ok = false else j += 1 }
                        if (ok && sroute) { if (pl < j + 1) ok = false else { j += 2 + 2 * (p[j].toInt() and 0xFF); if (pl < j) ok = false } }
                        if (ok) {
                            val who = "${short(nwkSrc!!)} > ${short(nwkDest!!)}"
                            info = when {
                                nsec -> { encrypted = true; "Zigbee NWK ${if (nt == 1) "command" else "data"} $who, radius $radius, seq $nseq, payload encrypted (network key)" }
                                nt == 1 -> "Zigbee NWK ${nwkCommand(if (pl > j) p[j].toInt() and 0xFF else -1)} $who, radius $radius"
                                else -> aps(p, j, pl)?.let { "Zigbee $who, $it" } ?: "Zigbee NWK data $who, radius $radius, seq $nseq"
                            }
                        }
                    }
                }
            }
            else -> info = "${frameTypeName(type)} ${if (sm == 3) ext(se) else short(ss)} > ${if (dm == 3) ext(de) else short(ds)}"
        }
        return frame()
    }

    /** The APS header of an unencrypted NWK data frame: cluster, profile and endpoints. */
    private fun aps(p: ByteArray, at: Int, n: Int): String? {
        if (n < at + 1) return null
        val fc = p[at].toInt() and 0xFF
        val t = fc and 3; val delivery = (fc shr 2) and 3; val secured = fc and 0x20 != 0
        var i = at + 1
        if (t != 0) return if (t == 1) "APS command" + (if (secured) ", APS encrypted" else "") else "APS ack"
        var dEp = -1
        if (delivery == 0 || delivery == 2) { if (n < i + 1) return null; dEp = p[i++].toInt() and 0xFF } else if (delivery == 3) i += 2
        if (n < i + 6) return null
        val cluster = le16(p, i); val profile = le16(p, i + 2); val sEp = p[i + 4].toInt() and 0xFF
        val cn = clusterName(cluster, profile)
        val pn = profileName(profile)
        return "APS cluster 0x%04X".format(cluster) + (cn?.let { " $it" } ?: "") + ", profile 0x%04X".format(profile) + (pn?.let { " ($it)" } ?: "") +
            ", ep $sEp > $dEp" + if (secured) ", APS encrypted" else ""
    }

    /** A MAC frame with its FCS (tests). */
    fun buildMac(type: Int, seq: Int, pan: Int, dst: Int, src: Int, payload: ByteArray, ackReq: Boolean = false): ByteArray {
        val fc = type or (if (ackReq) 1 shl 5 else 0) or (1 shl 6) or (2 shl 10) or (2 shl 14)
        val b = byteArrayOf(fc.toByte(), (fc shr 8).toByte(), seq.toByte(), pan.toByte(), (pan shr 8).toByte(), dst.toByte(), (dst shr 8).toByte(), src.toByte(), (src shr 8).toByte()) + payload
        val c = crc16(b, b.size)
        return b + byteArrayOf(c.toByte(), (c shr 8).toByte())
    }
}

/** A frame received with a good FCS. */
class ZigbeeHeard(val frame: Zigbee.Frame, val raw: ByteArray, val channel: Int, val snrDb: Double)

/**
 * The 2.4 GHz O-QPSK PHY at 4 MS/s (two samples a chip), one channel centred: O-QPSK with half-sine shaping is MSK, so
 * the sign of the phase turn over each chip (the imaginary part of x[k]·conj(x[k−2])) is one bit of a known 32-chip
 * pattern per symbol. Both sample phases and both spectral orientations are tried. The preamble (eight symbols 0) is
 * found with up to 4 chip errors; then symbols are read 32 chips apart (closest pattern, chip 0 ignored since it depends
 * on the previous symbol) through the start-of-frame delimiter (7, A), the length and the PSDU, whose FCS decides.
 */
class ZigbeeReceiver(@Volatile var channel: Int, private val onFrame: (ZigbeeHeard) -> Unit) {
    private val pat = Array(16) { s ->
        // Sign of the frequency during chip n: c_n · c_{n−1} · (−1)^(n+1) (OnAir zigbee_rx, from the O-QPSK half-sine definition).
        var r = 0
        val t = Zigbee.CHIPS[s]
        for (n in 0 until 32) {
            val cn = if ((t ushr (31 - n)) and 1 == 1) 1 else -1
            val cp = if ((t ushr (31 - (n + 31) % 32)) and 1 == 1) 1 else -1
            if (cn * cp * (if (n % 2 == 0) -1 else 1) > 0) r = r or (1 shl (31 - n))
        }
        r
    }
    private val mask = 0x7FFFFFFF
    private val ringBits = 1 shl 15

    /** One chip stream: a sample phase and an orientation. */
    private inner class Stream(val inv: Boolean) {
        val chips = ByteArray(ringBits)
        var count = 0L
        var reg = 0
        val pending = ArrayList<Long>()
        var until = 0L

        fun push(c: Int) {
            val b = if (inv) c xor 1 else c
            chips[(count and (ringBits - 1).toLong()).toInt()] = b.toByte()
            count++
            reg = (reg shl 1) or b
            if (count >= until && Integer.bitCount((reg xor pat[0]) and mask) <= 4 && (pending.isEmpty() || count - pending.last() > 32)) pending += count
            if (pending.isNotEmpty()) run()
        }

        private fun word(end: Long): Int {
            var w = 0
            for (k in 0 until 32) w = (w shl 1) or chips[((end - 32 + k) and (ringBits - 1).toLong()).toInt()].toInt()
            return w
        }

        private fun sym(end: Long): Int {
            val w = word(end)
            var best = -1; var bd = 99
            for (s in 0 until 16) { val d = Integer.bitCount((w xor pat[s]) and mask); if (d < bd) { bd = d; best = s } }
            return if (bd <= 9) best else -1
        }

        /** -1: needs more chips, 0: not a frame, 1: done. */
        private fun attempt(c: Long): Int {
            var k = c
            var zeros = 0
            while (true) {
                val e = k + 32
                if (e > count) return -1
                val s = sym(e)
                if (s == 0 && zeros < 12) { zeros++; k = e; continue }
                if (s != 7) return 0
                if (e + 32 > count) return -1
                if (sym(e + 32) != 0xA) return 0
                k = e + 32
                break
            }
            if (k + 64 > count) return -1
            val lo = sym(k + 32); val hi = sym(k + 64)
            if (lo < 0 || hi < 0) return 0
            val len = (lo or (hi shl 4)) and 0x7F
            if (len < 5) return 0
            val end = k + 64 + 64L * len
            if (end > count) return -1
            if (count - end > ringBits - 1000) return 0
            val psdu = ByteArray(len)
            for (i in 0 until len) {
                val a = sym(k + 64 + 32L * (2 * i + 1)); val b = sym(k + 64 + 32L * (2 * i + 2))
                if (a < 0 || b < 0) return 0
                psdu[i] = (a or (b shl 4)).toByte()
            }
            if (Zigbee.crc16(psdu, len - 2) != ((psdu[len - 2].toInt() and 0xFF) or ((psdu[len - 1].toInt() and 0xFF) shl 8))) return 0
            until = end
            emit(psdu)
            return 1
        }

        private fun run() {
            val it = pending.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c < until) { it.remove(); continue }
                when (attempt(c)) { -1 -> return; else -> it.remove() }
            }
        }
    }

    private val streams = Array(2) { ph -> Array(2) { Stream(it == 1) } }
    private val hist = FloatArray(4)
    private var k = 0L
    private var dcR = 0.0
    private var dcI = 0.0
    private var noise = 1e-6
    private var sig = 0.0
    private val recent = ArrayDeque<Pair<Long, Int>>()

    /** Frames received with a good FCS, all streams together. */
    @Volatile var frames = 0; private set

    private fun emit(psdu: ByteArray) {
        // The same frame from another sample phase or orientation is one frame.
        val h = psdu.contentHashCode()
        while (recent.isNotEmpty() && k - recent.first().first > 40_000) recent.removeFirst()
        if (recent.any { it.second == h }) return
        recent.addLast(k to h)
        val f = Zigbee.parse(psdu) ?: return
        frames++
        onFrame(ZigbeeHeard(f, psdu, channel, 10 * kotlin.math.log10(maxOf(1e-3, sig / noise))))
    }

    /** Complex samples at 4 MS/s with the channel at zero. */
    fun feed(re: FloatArray, im: FloatArray, n: Int) {
        for (i in 0 until n) {
            // The HackRF's DC spike, taken away slowly (a narrow notch the 2 MHz signal hardly feels).
            // Quick at first (an average of what came so far), then slow.
            val a = if (k < 10_000) 1.0 / (k + 1) else 1e-4
            dcR += a * (re[i] - dcR); dcI += a * (im[i] - dcI)
            val xr = (re[i] - dcR).toFloat(); val xi = (im[i] - dcI).toFloat()
            val slot = (k and 1L).toInt()
            val pr = hist[2 * slot]; val pi = hist[2 * slot + 1]
            hist[2 * slot] = xr; hist[2 * slot + 1] = xi
            val v = xi * pr - xr * pi
            val p = xr * xr + xi * xi
            sig += 0.001 * (p - sig)
            if (p < 3 * noise || noise < 1e-5) noise += 0.0002 * (p - noise)
            val c = if (v > 0) 1 else 0
            for (s in streams[slot]) s.push(c)
            k++
        }
    }

    /** HackRF bytes at 4 MS/s. */
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
