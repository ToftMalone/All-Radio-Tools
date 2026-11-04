package com.allnetworktools.data.sdr

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Meshtastic over the air (firmware 2.x, src/mesh/RadioInterface.h and the protobufs of meshtastic/protobufs):
 *
 * - A 16-byte header in the clear: destination, sender, packet id (little-endian), flags (hop limit, want-ack, via MQTT,
 *   hop start), channel hash, next hop and relaying node.
 * - The rest is a `Data` protobuf encrypted with AES-CTR under the channel key; the nonce is the packet id (8 bytes) then
 *   the sender (4 bytes) then zeros.
 *
 * Only what anyone can read is decoded: channels using the public default key (the "AQ==" every node ships with, under the
 * name of any modem preset), or no key at all. Channels with a key of their own and direct messages (end-to-end, PKI)
 * stay encrypted: only their header is shown.
 */
object Meshtastic {
    const val SYNC_WORD = 0x2B
    const val BROADCAST = -1 // 0xFFFFFFFF

    /** The default channel key, "AQ==" in the apps (firmware src/mesh/Channels.h). */
    val DEFAULT_PSK = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )

    /**
     * Names of the modem presets as the firmware writes them (DisplayFormatters): a primary channel left unnamed takes the
     * name of its preset, and that name enters the channel hash.
     */
    val PRESET_NAMES = listOf(
        "LongFast", "MediumFast", "MediumSlow", "ShortFast", "ShortSlow", "LongMod", "LongSlow", "ShortTurbo", "LongTurbo",
        "MediumTurbo", "LiteFast", "LiteSlow", "NarrowFast", "NarrowSlow", "TinyFast", "TinySlow",
    )

    /** A channel that can be read: its name, its key (empty: not encrypted) and the hash the header carries. */
    class Channel(val name: String, val key: ByteArray) {
        val hash: Int = channelHash(name, key)
    }

    private fun xorHash(b: ByteArray): Int { var h = 0; for (x in b) h = h xor (x.toInt() and 0xFF); return h }

    fun channelHash(name: String, key: ByteArray): Int = xorHash(name.toByteArray(Charsets.UTF_8)) xor xorHash(key)

    /** A channel key as the apps give it (base64 of 0, 1, 16 or 32 bytes) expanded as the firmware does; null if invalid. */
    fun expandKey(raw: ByteArray): ByteArray? = when (raw.size) {
        0 -> ByteArray(0)
        1 -> if (raw[0].toInt() == 0) ByteArray(0) else DEFAULT_PSK.copyOf().also { it[15] = (it[15] + (raw[0].toInt() and 0xFF) - 1).toByte() }
        16, 32 -> raw
        in 2..15 -> raw.copyOf(16)
        in 17..31 -> raw.copyOf(32)
        else -> null
    }

    /** The public channels: every preset name with the default key, and with no key. */
    val publicChannels: List<Channel> = PRESET_NAMES.flatMap { listOf(Channel(it, DEFAULT_PSK), Channel(it, ByteArray(0))) }

    // ---- header -----------------------------------------------------------------------------------------

    data class Header(
        val to: Int,
        val from: Int,
        val id: Int,
        val hopLimit: Int,
        val wantAck: Boolean,
        val viaMqtt: Boolean,
        val hopStart: Int,
        val channel: Int,
        val nextHop: Int,
        val relayNode: Int,
    ) {
        val broadcast get() = to == BROADCAST
        /** Hops already made, when the sender's firmware says how many it allowed (hop start, since 2.3). */
        val hops get() = if (hopStart > 0 && hopStart >= hopLimit) hopStart - hopLimit else null
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun header(p: ByteArray): Header? {
        if (p.size < 16) return null
        val f = p[12].toInt() and 0xFF
        return Header(le32(p, 0), le32(p, 4), le32(p, 8), f and 7, f and 8 != 0, f and 0x10 != 0, f shr 5, p[13].toInt() and 0xFF, p[14].toInt() and 0xFF, p[15].toInt() and 0xFF)
    }

    /** "!a1b2c3d4", as the apps name a node by its number. */
    fun nodeId(num: Int) = "!%08x".format(num)

    // ---- encryption -------------------------------------------------------------------------------------

    /** AES-CTR as the firmware runs it (the same for both directions); a key of 16 bytes is AES-128, of 32 AES-256. */
    fun crypt(key: ByteArray, from: Int, id: Int, data: ByteArray): ByteArray {
        if (key.isEmpty()) return data
        val nonce = ByteArray(16)
        for (i in 0 until 4) { nonce[i] = (id ushr (8 * i)).toByte(); nonce[8 + i] = (from ushr (8 * i)).toByte() }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce))
        return c.doFinal(data)
    }

    // ---- protobuf ---------------------------------------------------------------------------------------

    /** A strict reader of protobuf wire format: any inconsistency throws, so a wrong key is never taken for a message. */
    class Pb(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {
        var field = 0; private set
        var wire = 0; private set
        var long = 0L; private set
        var bytes: ByteArray = ByteArray(0); private set

        val int get() = long.toInt()
        val sint get() = ((long ushr 1) xor -(long and 1)).toInt()
        val float get() = java.lang.Float.intBitsToFloat(long.toInt())
        val string get() = String(bytes, Charsets.UTF_8)

        private fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                if (pos >= end || s > 63) throw IllegalArgumentException("varint")
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
            }
        }

        /** The next field, or false at the end. */
        fun next(): Boolean {
            if (pos >= end) return false
            val tag = varint()
            field = (tag ushr 3).toInt()
            wire = (tag and 7).toInt()
            if (field == 0) throw IllegalArgumentException("field 0")
            when (wire) {
                0 -> long = varint()
                1 -> { need(8); long = 0; for (i in 7 downTo 0) long = (long shl 8) or (b[pos + i].toLong() and 0xFF); pos += 8 }
                2 -> { val n = varint(); if (n < 0 || n > end - pos) throw IllegalArgumentException("len"); bytes = b.copyOfRange(pos, pos + n.toInt()); pos += n.toInt() }
                5 -> { need(4); long = le32(b, pos).toLong() and 0xFFFFFFFFL; pos += 4 }
                else -> throw IllegalArgumentException("wire $wire")
            }
            return true
        }

        private fun need(n: Int) { if (end - pos < n) throw IllegalArgumentException("short") }

        /** Packed or single values of a repeated scalar field, as ints. */
        fun repeatedInts(fixed: Boolean): List<Int> = if (wire == 2) {
            val r = Pb(bytes)
            val out = ArrayList<Int>()
            if (fixed) { var i = 0; while (i + 4 <= bytes.size) { out += le32(bytes, i); i += 4 } } else while (r.pos < r.end) out += r.varint().toInt()
            out
        } else listOf(long.toInt())
    }

    /** Reads every field of [b] with [on]; null when the bytes are not a well-formed protobuf. */
    private inline fun <T> parse(b: ByteArray, init: T, on: (Pb, T) -> Unit): T? = runCatching {
        val r = Pb(b)
        while (r.next()) on(r, init)
        init
    }.getOrNull()

    /** The `Data` message every packet carries once decrypted. */
    class DataMsg {
        var port = -1
        var payload = ByteArray(0)
        var wantResponse = false
        var dest = 0
        var source = 0
        var requestId = 0
        var replyId = 0
        var emoji = 0
    }

    /** Null unless [b] is a `Data` message with consistent wire types and a port number: the check that the key was right. */
    fun data(b: ByteArray): DataMsg? {
        var ok = true
        val d = parse(b, DataMsg()) { r, d ->
            fun expect(w: Int) { if (r.wire != w) ok = false }
            when (r.field) {
                1 -> { expect(0); d.port = r.int }
                2 -> { expect(2); d.payload = r.bytes }
                3 -> { expect(0); d.wantResponse = r.long != 0L }
                4 -> { expect(5); d.dest = r.int }
                5 -> { expect(5); d.source = r.int }
                6 -> { expect(5); d.requestId = r.int }
                7 -> { expect(5); d.replyId = r.int }
                8 -> { expect(5); d.emoji = r.int }
                // Fields added by later firmware are skipped.
            }
        } ?: return null
        // Encoders write the port number first: a decryption with the wrong key rarely starts with its tag.
        return if (ok && b.isNotEmpty() && b[0].toInt() == 0x08 && d.port in 0..511) d else null
    }

    // ---- applications -----------------------------------------------------------------------------------

    const val PORT_TEXT = 1
    const val PORT_POSITION = 3
    const val PORT_NODEINFO = 4
    const val PORT_ROUTING = 5
    const val PORT_WAYPOINT = 8
    const val PORT_TELEMETRY = 67
    const val PORT_TRACEROUTE = 70
    const val PORT_NEIGHBORINFO = 71
    const val PORT_MAP_REPORT = 73

    private val portNames = mapOf(
        0 to "UNKNOWN", 1 to "TEXT_MESSAGE", 2 to "REMOTE_HARDWARE", 3 to "POSITION", 4 to "NODEINFO", 5 to "ROUTING", 6 to "ADMIN",
        7 to "TEXT_MESSAGE_COMPRESSED", 8 to "WAYPOINT", 9 to "AUDIO", 10 to "DETECTION_SENSOR", 11 to "ALERT", 12 to "KEY_VERIFICATION",
        13 to "REMOTE_SHELL", 32 to "REPLY", 33 to "IP_TUNNEL", 34 to "PAXCOUNTER", 35 to "STORE_FORWARD_PLUSPLUS", 36 to "NODE_STATUS",
        37 to "MESH_BEACON", 38 to "PAGING", 64 to "SERIAL", 65 to "STORE_FORWARD", 66 to "RANGE_TEST", 67 to "TELEMETRY", 68 to "ZPS",
        69 to "SIMULATOR", 70 to "TRACEROUTE", 71 to "NEIGHBORINFO", 72 to "ATAK_PLUGIN", 73 to "MAP_REPORT", 74 to "POWERSTRESS",
        75 to "LORAWAN_BRIDGE", 76 to "RETICULUM_TUNNEL", 77 to "CAYENNE", 79 to "LORA_OTA", 112 to "GROUPALARM", 256 to "PRIVATE",
        257 to "ATAK_FORWARDER",
    )

    fun portName(port: Int) = portNames[port] ?: "PORT_$port"

    data class Position(
        val lat: Double?, val lon: Double?, val altitudeM: Int?, val timeS: Long?, val speedMs: Int?, val trackDeg: Double?,
        val sats: Int?, val precisionBits: Int?,
    )

    fun position(b: ByteArray): Position? {
        var lat: Int? = null; var lon: Int? = null; var alt: Int? = null; var time: Long? = null
        var speed: Int? = null; var track: Int? = null; var sats: Int? = null; var prec: Int? = null
        parse(b, Unit) { r, _ ->
            when (r.field) {
                1 -> lat = r.int
                2 -> lon = r.int
                3 -> alt = r.int
                4 -> time = r.long and 0xFFFFFFFFL
                15 -> speed = r.int
                16 -> track = r.int
                19 -> sats = r.int
                23 -> prec = r.int
            }
        } ?: return null
        // 0/0 is what a node without a fix sends.
        val la = lat?.takeIf { it != 0 && it in -900_000_000..900_000_000 }?.let { it / 1e7 }
        val lo = lon?.takeIf { it != 0 && it in -1_800_000_000..1_800_000_000 }?.let { it / 1e7 }
        return Position(la?.takeIf { lo != null }, lo?.takeIf { la != null }, alt, time?.takeIf { it > 0 }, speed, track?.let { it / 1e5 }, sats?.takeIf { it > 0 }, prec?.takeIf { it > 0 })
    }

    data class User(val id: String?, val longName: String?, val shortName: String?, val hwModel: Int?, val role: Int?, val licensed: Boolean, val hasPublicKey: Boolean)

    fun user(b: ByteArray): User? {
        var id: String? = null; var ln: String? = null; var sn: String? = null; var hw: Int? = null; var role: Int? = null
        var lic = false; var pk = false
        parse(b, Unit) { r, _ ->
            when (r.field) {
                1 -> id = r.string
                2 -> ln = r.string
                3 -> sn = r.string
                5 -> hw = r.int
                6 -> lic = r.long != 0L
                7 -> role = r.int
                8 -> pk = r.bytes.size == 32
            }
        } ?: return null
        return User(id, ln?.takeIf { it.isNotBlank() }, sn?.takeIf { it.isNotBlank() }, hw, role, lic, pk)
    }

    data class Telemetry(
        val battery: Int? = null, val voltage: Float? = null, val channelUtil: Float? = null, val airUtilTx: Float? = null, val uptimeS: Int? = null,
        val temperature: Float? = null, val humidity: Float? = null, val pressure: Float? = null, val lux: Float? = null, val windSpeed: Float? = null,
        val windDir: Int? = null, val envVoltage: Float? = null, val current: Float? = null, val iaq: Int? = null,
    )

    fun telemetry(b: ByteArray): Telemetry? {
        var t = Telemetry()
        parse(b, Unit) { r, _ ->
            when (r.field) {
                2 -> parse(r.bytes, Unit) { m, _ ->
                    when (m.field) {
                        1 -> t = t.copy(battery = m.int)
                        2 -> t = t.copy(voltage = m.float)
                        3 -> t = t.copy(channelUtil = m.float)
                        4 -> t = t.copy(airUtilTx = m.float)
                        5 -> t = t.copy(uptimeS = m.int)
                    }
                }
                3 -> parse(r.bytes, Unit) { m, _ ->
                    when (m.field) {
                        1 -> t = t.copy(temperature = m.float)
                        2 -> t = t.copy(humidity = m.float)
                        3 -> t = t.copy(pressure = m.float)
                        5 -> t = t.copy(envVoltage = m.float)
                        6 -> t = t.copy(current = m.float)
                        7 -> t = t.copy(iaq = m.int)
                        9 -> t = t.copy(lux = m.float)
                        13 -> t = t.copy(windDir = m.int)
                        14 -> t = t.copy(windSpeed = m.float)
                    }
                }
            }
        } ?: return null
        return t
    }

    /** Traceroute: the nodes the request went through, and back. */
    data class Route(val towards: List<Int>, val back: List<Int>)

    fun route(b: ByteArray): Route? {
        val t = ArrayList<Int>(); val bk = ArrayList<Int>()
        parse(b, Unit) { r, _ ->
            when (r.field) {
                1 -> t += r.repeatedInts(fixed = true)
                3 -> bk += r.repeatedInts(fixed = true)
            }
        } ?: return null
        return Route(t, bk)
    }

    /** Neighbour report: the nodes a node hears directly, with the SNR it hears them at. */
    fun neighbors(b: ByteArray): List<Pair<Int, Float?>>? {
        val out = ArrayList<Pair<Int, Float?>>()
        parse(b, Unit) { r, _ ->
            if (r.field == 4 && r.wire == 2) {
                var id = 0; var snr: Float? = null
                parse(r.bytes, Unit) { m, _ -> when (m.field) { 1 -> id = m.int; 2 -> snr = m.float } }
                if (id != 0) out += id to snr
            }
        } ?: return null
        return out
    }

    data class Waypoint(val name: String?, val description: String?, val lat: Double?, val lon: Double?)

    fun waypoint(b: ByteArray): Waypoint? {
        var name: String? = null; var desc: String? = null; var lat: Int? = null; var lon: Int? = null
        parse(b, Unit) { r, _ ->
            when (r.field) {
                2 -> lat = r.int
                3 -> lon = r.int
                6 -> name = r.string
                7 -> desc = r.string
            }
        } ?: return null
        return Waypoint(name, desc, lat?.let { it / 1e7 }, lon?.let { it / 1e7 })
    }

    data class MapReport(val longName: String?, val shortName: String?, val role: Int?, val hwModel: Int?, val firmware: String?, val lat: Double?, val lon: Double?, val altitudeM: Int?, val onlineNodes: Int?)

    fun mapReport(b: ByteArray): MapReport? {
        var ln: String? = null; var sn: String? = null; var role: Int? = null; var hw: Int? = null; var fw: String? = null
        var lat: Int? = null; var lon: Int? = null; var alt: Int? = null; var online: Int? = null
        parse(b, Unit) { r, _ ->
            when (r.field) {
                1 -> ln = r.string
                2 -> sn = r.string
                3 -> role = r.int
                4 -> hw = r.int
                5 -> fw = r.string
                9 -> lat = r.int
                10 -> lon = r.int
                11 -> alt = r.int
                13 -> online = r.int
            }
        } ?: return null
        return MapReport(ln, sn, role, hw, fw, lat?.takeIf { it != 0 }?.let { it / 1e7 }, lon?.takeIf { it != 0 }?.let { it / 1e7 }, alt, online)
    }

    /** The error code of a routing reply (0: delivered). */
    fun routingError(b: ByteArray): Int? {
        var err: Int? = null
        parse(b, Unit) { r, _ -> if (r.field == 3 && r.wire == 0) err = r.int } ?: return null
        return err
    }

    // ---- packet -----------------------------------------------------------------------------------------

    /** A received packet: the header, and what it says when one of the public channels opens it. */
    class Packet(val raw: ByteArray, val header: Header, val channel: Channel?, val data: DataMsg?) {
        /** A direct message encrypted for its recipient alone (channel hash 0, firmware 2.5 and later). */
        val pki get() = data == null && header.channel == 0 && !header.broadcast
    }

    /** Null when [raw] is too short to be Meshtastic. */
    fun decode(raw: ByteArray, channels: List<Channel> = publicChannels): Packet? {
        val h = header(raw) ?: return null
        val body = raw.copyOfRange(16, raw.size)
        if (body.isEmpty()) return Packet(raw, h, null, null)
        for (c in channels) {
            if (c.hash != h.channel) continue
            val d = data(crypt(c.key, h.from, h.id, body)) ?: continue
            return Packet(raw, h, c, d)
        }
        return Packet(raw, h, null, null)
    }

    // ---- names -----------------------------------------------------------------------------------------

    private val roles = listOf(
        "CLIENT", "CLIENT_MUTE", "ROUTER", "ROUTER_CLIENT", "REPEATER", "TRACKER", "SENSOR", "TAK", "CLIENT_HIDDEN",
        "LOST_AND_FOUND", "TAK_TRACKER", "ROUTER_LATE", "CLIENT_BASE",
    )

    fun roleName(role: Int) = roles.getOrNull(role) ?: "ROLE_$role"

    /** Hardware models (protobufs mesh.proto, HardwareModel). */
    private val hardware: Map<Int, String> = (
        "1 TLORA_V2,2 TLORA_V1,3 TLORA_V2_1_1P6,4 TBEAM,5 HELTEC_V2_0,6 TBEAM_V0P7,7 T_ECHO,8 TLORA_V1_1P3,9 RAK4631," +
            "10 HELTEC_V2_1,11 HELTEC_V1,12 LILYGO_TBEAM_S3_CORE,13 RAK11200,14 NANO_G1,15 TLORA_V2_1_1P8,16 TLORA_T3_S3," +
            "17 NANO_G1_EXPLORER,18 NANO_G2_ULTRA,19 LORA_TYPE,20 WIPHONE,21 WIO_WM1110,22 RAK2560,23 HELTEC_HRU_3601," +
            "24 HELTEC_WIRELESS_BRIDGE,25 STATION_G1,26 RAK11310,27 MAKERFABS_TRACKER,29 CANARYONE,30 RP2040_LORA," +
            "31 STATION_G2,32 LORA_RELAY_V1,33 T_ECHO_PLUS,34 PPR,35 GENIEBLOCKS,36 NRF52_UNKNOWN,37 PORTDUINO," +
            "38 ANDROID_SIM,39 DIY_V1,40 NRF52840_PCA10059,41 DR_DEV,42 M5STACK,43 HELTEC_V3,44 HELTEC_WSL_V3," +
            "45 BETAFPV_2400_TX,46 BETAFPV_900_NANO_TX,47 RPI_PICO,48 HELTEC_WIRELESS_TRACKER,49 HELTEC_WIRELESS_PAPER," +
            "50 T_DECK,51 T_WATCH_S3,52 PICOMPUTER_S3,53 HELTEC_HT62,54 EBYTE_ESP32_S3,55 ESP32_S3_PICO,56 CHATTER_2," +
            "57 HELTEC_WIRELESS_PAPER_V1_0,58 HELTEC_WIRELESS_TRACKER_V1_0,59 UNPHONE,60 TD_LORAC,61 CDEBYTE_EORA_S3," +
            "62 TWC_MESH_V4,63 NRF52_PROMICRO_DIY,64 RADIOMASTER_900_BANDIT_NANO,65 HELTEC_CAPSULE_SENSOR_V3," +
            "66 HELTEC_VISION_MASTER_T190,67 HELTEC_VISION_MASTER_E213,68 HELTEC_VISION_MASTER_E290," +
            "69 HELTEC_MESH_NODE_T114,70 SENSECAP_INDICATOR,71 TRACKER_T1000_E,72 RAK3172,73 WIO_E5," +
            "74 RADIOMASTER_900_BANDIT,75 ME25LS01_4Y10TD,76 RP2040_FEATHER_RFM95,77 M5STACK_COREBASIC,78 M5STACK_CORE2," +
            "79 RPI_PICO2,80 M5STACK_CORES3,81 SEEED_XIAO_S3,82 MS24SF1,83 TLORA_C6,84 WISMESH_TAP,85 ROUTASTIC," +
            "86 MESH_TAB,87 MESHLINK,88 XIAO_NRF52_KIT,89 THINKNODE_M1,90 THINKNODE_M2,91 T_ETH_ELITE,92 HELTEC_SENSOR_HUB," +
            "93 MUZI_BASE,94 HELTEC_MESH_POCKET,95 SEEED_SOLAR_NODE,96 NOMADSTAR_METEOR_PRO,97 CROWPANEL,98 LINK_32," +
            "99 SEEED_WIO_TRACKER_L1,100 SEEED_WIO_TRACKER_L1_EINK,101 MUZI_R1_NEO,102 T_DECK_PRO,103 T_LORA_PAGER," +
            "105 WISMESH_TAG,106 RAK3312,107 THINKNODE_M5,108 HELTEC_MESH_SOLAR,109 T_ECHO_LITE,110 HELTEC_V4," +
            "111 M5STACK_C6L,112 M5STACK_CARDPUTER_ADV,113 HELTEC_WIRELESS_TRACKER_V2,114 T_WATCH_ULTRA,115 THINKNODE_M3," +
            "116 WISMESH_TAP_V2,117 RAK3401,118 RAK6421,119 THINKNODE_M4,120 THINKNODE_M6,121 MESHSTICK_1262," +
            "122 TBEAM_1_WATT,123 T5_S3_EPAPER_PRO,124 TBEAM_BPF,125 MINI_EPAPER_S3,126 TDISPLAY_S3_PRO," +
            "127 HELTEC_MESH_NODE_T096,128 MESH_TRACKER_X1,129 THINKNODE_M7,130 THINKNODE_M8,131 THINKNODE_M9," +
            "132 HELTEC_V4_R8,133 HELTEC_MESH_NODE_T1,134 STATION_G3,135 T_IMPULSE_PLUS,136 T_ECHO_CARD," +
            "137 SEEED_WIO_TRACKER_L2,138 CROWPANEL_P4,139 HELTEC_MESH_TOWER_V2,140 MESHNOLOGY_W10,141 HELTEC_RC32," +
            "142 HELTEC_RC52,143 HELTEC_RCC6,144 SEEED_WIO_TRACKER_L1_PRO_1W,145 MESHNOLOGY_W12,146 MESHPAGER_X2," +
            "147 T_CONNECT_PRO,148 AXIOMETA_GENESIS_MINI,149 MAKERFABS_NOMAD_TERMINAL,150 THINKNODE_MX,151 NODARA," +
            "152 HELTEC_MESH_POCKET_V2,255 PRIVATE_HW"
        ).split(',').associate { e -> e.substringBefore(' ').toInt() to e.substringAfter(' ') }

    fun hardwareName(hw: Int) = hardware[hw] ?: if (hw == 0) null else "HW $hw"

    // ---- bands ------------------------------------------------------------------------------------------

    /**
     * EU_868 (869.4 to 869.65 MHz): every preset of the 250 kHz family on the one channel 869.525 MHz, LongSlow and LongMod
     * on the 125 kHz slot their name hashes to, and the narrow presets of EU_N_868 on their fixed first slot.
     */
    val EU868 = MeshBand(
        869_025_000L, wide = false,
        listOf(
            MeshChannel(869_525_000L, 250_000.0, listOf(7, 8, 9, 10, 11)),
            MeshChannel(869_462_500L, 125_000.0, listOf(12)),
            MeshChannel(869_587_500L, 125_000.0, listOf(11)),
            MeshChannel(869_441_650L, 62_500.0, listOf(7, 8)),
        ),
    )

    /** EU_433 (433.0 to 434.0 MHz): each preset on the slot of its name (djb2 hash of the name modulo the slot count). */
    val EU433 = MeshBand(
        433_500_000L, wide = true,
        listOf(
            MeshChannel(433_875_000L, 250_000.0, listOf(7, 10, 11)),
            MeshChannel(433_125_000L, 250_000.0, listOf(9)),
            MeshChannel(433_625_000L, 250_000.0, listOf(8)),
            MeshChannel(433_312_500L, 125_000.0, listOf(12)),
            MeshChannel(433_687_500L, 125_000.0, listOf(11)),
        ),
    )

    /** The preset a frame's modulation belongs to (names as in the apps). */
    fun presetFor(sf: Int, bwHz: Double): String? = when (bwHz.toInt()) {
        250_000 -> when (sf) { 7 -> "ShortFast"; 8 -> "ShortSlow"; 9 -> "MediumFast"; 10 -> "MediumSlow"; 11 -> "LongFast"; else -> null }
        125_000 -> when (sf) { 11 -> "LongMod"; 12 -> "LongSlow"; 9 -> "LiteFast"; 10 -> "LiteSlow"; else -> null }
        62_500 -> when (sf) { 7 -> "NarrowFast"; 8 -> "NarrowSlow"; else -> null }
        else -> null
    }

    /** djb2, as the firmware hashes a channel name to pick its frequency slot. */
    fun slotHash(name: String): Long {
        var h = 5381L
        for (c in name.toByteArray(Charsets.UTF_8)) h = ((h shl 5) + h + (c.toInt() and 0xFF)) and 0xFFFFFFFFL
        return h
    }
}
