package com.allnetworktools.data.sdr

import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Meshtastic over-the-air format (firmware src/mesh/RadioInterface.h, CryptoEngine.cpp, Channels.cpp and the
 * meshtastic/protobufs definitions): a 16-byte clear header, then the channel-encrypted `Data` protobuf.
 */
object Meshtastic {
    const val BROADCAST = 0xFFFFFFFFL

    /** The firmware's default channel key, the one a 1-byte key of 1 ("AQ==") stands for. */
    private val DEFAULT_PSK = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )

    /** Decodes a channel key typed as Base64 (as the official app shows it); null when it is not valid Base64. */
    fun parseKey(base64: String): ByteArray? {
        val s = base64.trim().replace('-', '+').replace('_', '/')
        return runCatching { Base64.getDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '=')) }.getOrNull()
            ?.takeIf { it.size in setOf(0, 1, 16, 32) }
    }

    /** The AES key a channel key stands for, as the firmware expands it: 1 byte = a variant of the default key, 0 = none. */
    fun expandKey(psk: ByteArray): ByteArray? = when {
        psk.isEmpty() -> null
        psk.size == 1 -> {
            val idx = psk[0].toInt() and 0xFF
            if (idx == 0) null else DEFAULT_PSK.copyOf().also { it[15] = (it[15] + idx - 1).toByte() }
        }
        else -> psk.copyOf()
    }

    /** Channel hash sent in clear in each packet: XOR of the name's bytes and of the expanded key's bytes. */
    fun channelHash(name: String, key: ByteArray?): Int {
        var h = 0
        name.toByteArray(Charsets.UTF_8).forEach { h = h xor (it.toInt() and 0xFF) }
        key?.forEach { h = h xor (it.toInt() and 0xFF) }
        return h
    }

    class Header(
        val to: Long,
        val from: Long,
        val id: Long,
        val hopLimit: Int,
        val wantAck: Boolean,
        val viaMqtt: Boolean,
        val hopStart: Int,
        val channel: Int,
        val nextHop: Int,
        val relayNode: Int,
    ) {
        /** How many times the packet was relayed before reaching us, when the sender says how many hops it allowed. */
        val hops: Int? get() = if (hopStart in 1..7 && hopStart >= hopLimit) hopStart - hopLimit else null
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    fun header(b: ByteArray): Header? {
        if (b.size < 16) return null
        val flags = b[12].toInt() and 0xFF
        return Header(
            to = u32(b, 0), from = u32(b, 4), id = u32(b, 8),
            hopLimit = flags and 0x07, wantAck = flags and 0x08 != 0, viaMqtt = flags and 0x10 != 0, hopStart = (flags and 0xE0) shr 5,
            channel = b[13].toInt() and 0xFF, nextHop = b[14].toInt() and 0xFF, relayNode = b[15].toInt() and 0xFF,
        )
    }

    /** AES-CTR with nonce = packet id (8 bytes LE) ‖ sender (4 bytes LE) ‖ block counter; it both encrypts and decrypts. */
    fun crypt(key: ByteArray, from: Long, id: Long, data: ByteArray): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 4) iv[i] = (id shr (8 * i)).toByte()
        for (i in 0 until 4) iv[8 + i] = (from shr (8 * i)).toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    // ---- minimal protobuf reader --------------------------------------------------------------------

    class Field(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?)

    /** Parses a protobuf message into its fields; null when the bytes are not a well-formed message. */
    fun fields(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long? {
            var r = 0L
            var shift = 0
            while (i < b.size && shift < 64) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        while (i < b.size) {
            val key = varint() ?: return null
            val num = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            if (num <= 0) return null
            when (wire) {
                0 -> out += Field(num, 0, varint() ?: return null, null)
                1 -> { if (i + 8 > b.size) return null; out += Field(num, 1, u32(b, i) or (u32(b, i + 4) shl 32), b.copyOfRange(i, i + 8)); i += 8 }
                2 -> {
                    val len = varint() ?: return null
                    if (len < 0 || i + len > b.size) return null
                    out += Field(num, 2, len, b.copyOfRange(i, i + len.toInt())); i += len.toInt()
                }
                5 -> { if (i + 4 > b.size) return null; out += Field(num, 5, u32(b, i), b.copyOfRange(i, i + 4)); i += 4 }
                else -> return null
            }
        }
        return out
    }

    private fun List<Field>.f(n: Int) = firstOrNull { it.number == n }
    private fun Field.str() = bytes?.toString(Charsets.UTF_8)
    private fun Field.float() = java.lang.Float.intBitsToFloat(varint.toInt())
    private fun Field.int32() = varint.toInt()

    /** Values of a repeated fixed32 field, packed or not. */
    private fun List<Field>.fixed32s(n: Int): List<Long> = filter { it.number == n }.flatMap { f ->
        if (f.wire == 5) listOf(f.varint)
        else f.bytes?.let { b -> (0 until b.size / 4).map { u32(b, it * 4) } } ?: emptyList()
    }

    /** Values of a repeated int32 field, packed or not. */
    private fun List<Field>.int32s(n: Int): List<Int> = filter { it.number == n }.flatMap { f ->
        if (f.wire == 0) listOf(f.int32())
        else f.bytes?.let { b ->
            val out = ArrayList<Int>()
            var i = 0
            while (i < b.size) {
                var r = 0L
                var shift = 0
                while (i < b.size) {
                    val x = b[i++].toInt() and 0xFF
                    r = r or ((x and 0x7F).toLong() shl shift)
                    shift += 7
                    if (x and 0x80 == 0) break
                }
                out += r.toInt()
            }
            out
        } ?: emptyList()
    }

    // ---- decoded content -----------------------------------------------------------------------------

    sealed interface Content {
        data class Text(val text: String) : Content
        data class Position(val lat: Double?, val lon: Double?, val altitude: Int?, val sats: Int?, val speedKmh: Int?) : Content
        data class NodeInfo(val id: String?, val longName: String?, val shortName: String?, val hwModel: Int?, val role: Int?, val hasPublicKey: Boolean) : Content
        data class Telemetry(
            val battery: Int?, val voltage: Float?, val chUtil: Float?, val airUtil: Float?, val uptime: Long?,
            val temperature: Float?, val humidity: Float?, val pressure: Float?,
        ) : Content
        data class Routing(val error: Int?) : Content
        data class Traceroute(val route: List<Long>, val snrTowards: List<Int>, val back: List<Long>) : Content
        data class Neighbors(val node: Long?, val neighbors: List<Pair<Long, Float?>>) : Content
        data class Waypoint(val name: String?, val description: String?, val lat: Double?, val lon: Double?) : Content
        data class MapReport(val longName: String?, val shortName: String?, val hwModel: Int?, val firmware: String?, val lat: Double?, val lon: Double?) : Content
        data class Other(val port: Int, val size: Int) : Content
    }

    class Data(val port: Int, val content: Content, val requestId: Long?, val wantResponse: Boolean)

    /** Decodes a decrypted `Data` message; null when it does not parse, i.e. wrong key or not for this channel. */
    fun data(plain: ByteArray): Data? {
        val f = fields(plain) ?: return null
        val portField = f.f(1) ?: return null
        if (portField.wire != 0) return null
        val port = portField.varint.toInt()
        if (port !in 0..511) return null
        val payloadField = f.f(2)
        if (payloadField != null && payloadField.wire != 2) return null
        val payload = payloadField?.bytes ?: ByteArray(0)
        val content: Content = runCatching { content(port, payload) }.getOrNull() ?: Content.Other(port, payload.size)
        return Data(port, content, f.f(6)?.varint, f.f(3)?.varint == 1L)
    }

    private fun coord(f: Field?): Double? = f?.let { it.int32() / 1e7 }?.takeIf { it != 0.0 }

    private fun content(port: Int, payload: ByteArray): Content? = when (port) {
        1, 10, 66 -> Content.Text(payload.toString(Charsets.UTF_8))
        3 -> fields(payload)?.let { p ->
            Content.Position(coord(p.f(1)), coord(p.f(2)), p.f(3)?.int32(), p.f(19)?.int32(), p.f(15)?.let { (it.varint * 36 / 10).toInt() })
        }
        4 -> fields(payload)?.let { u ->
            Content.NodeInfo(u.f(1)?.str(), u.f(2)?.str(), u.f(3)?.str(), u.f(5)?.int32(), u.f(7)?.int32(), (u.f(8)?.bytes?.size ?: 0) > 0)
        }
        5 -> fields(payload)?.let { r -> Content.Routing(r.f(3)?.int32()) }
        8 -> fields(payload)?.let { w -> Content.Waypoint(w.f(6)?.str(), w.f(7)?.str(), coord(w.f(2)), coord(w.f(3))) }
        67 -> fields(payload)?.let { t ->
            val dev = t.f(2)?.bytes?.let(::fields)
            val env = t.f(3)?.bytes?.let(::fields)
            Content.Telemetry(
                battery = dev?.f(1)?.int32(), voltage = dev?.f(2)?.float(), chUtil = dev?.f(3)?.float(), airUtil = dev?.f(4)?.float(),
                uptime = dev?.f(5)?.varint, temperature = env?.f(1)?.float(), humidity = env?.f(2)?.float(), pressure = env?.f(3)?.float(),
            )
        }
        70 -> fields(payload)?.let { r -> Content.Traceroute(r.fixed32s(1), r.int32s(2), r.fixed32s(3)) }
        71 -> fields(payload)?.let { r ->
            Content.Neighbors(r.f(1)?.varint, r.filter { it.number == 4 }.mapNotNull { it.bytes?.let(::fields) }.map { nb -> (nb.f(1)?.varint ?: 0L) to nb.f(2)?.float() })
        }
        73 -> fields(payload)?.let { m ->
            Content.MapReport(m.f(1)?.str(), m.f(2)?.str(), m.f(4)?.int32(), m.f(5)?.str(), coord(m.f(9)), coord(m.f(10)))
        }
        else -> null
    }

    fun portName(port: Int): String = when (port) {
        0 -> S(R.string.unknown)
        1 -> "Message"
        2 -> S(R.string.remote_hardware)
        3 -> "Position"
        4 -> S(R.string.node_info)
        5 -> S(R.string.routing_ack)
        6 -> S(R.string.admin)
        7 -> S(R.string.compressed_message)
        8 -> S(R.string.waypoint)
        9 -> "Audio"
        10 -> S(R.string.detection_sensor)
        11 -> S(R.string.alert)
        12 -> S(R.string.key_verification)
        32 -> S(R.string.reply)
        33 -> S(R.string.ip_tunnel)
        34 -> S(R.string.paxcounter)
        64 -> S(R.string.serial)
        65 -> S(R.string.store_and_forward)
        66 -> S(R.string.range_test)
        67 -> S(R.string.telemetry)
        68 -> "ZPS"
        69 -> S(R.string.simulator)
        70 -> "Traceroute"
        71 -> S(R.string.neighbours)
        72 -> "ATAK"
        73 -> S(R.string.map_report)
        74 -> S(R.string.power_stress_test)
        76 -> "Reticulum"
        256 -> S(R.string.private_text)
        257 -> "ATAK"
        else -> "Port $port"
    }

    fun routingError(code: Int): String = when (code) {
        0 -> S(R.string.acknowledged)
        1 -> S(R.string.no_route)
        2 -> S(R.string.refused_nak)
        3 -> S(R.string.timed_out)
        4 -> S(R.string.no_interface)
        5 -> S(R.string.max_retransmissions)
        6 -> S(R.string.unknown_channel)
        7 -> S(R.string.too_large)
        8 -> S(R.string.no_response)
        9 -> S(R.string.duty_cycle_limit)
        32 -> S(R.string.bad_request)
        33 -> S(R.string.not_authorised)
        34, 35, 39 -> S(R.string.public_key_encryption_failed)
        38 -> S(R.string.rate_limit_exceeded)
        else -> S(R.string.error, code)
    }

    fun role(code: Int): String = when (code) {
        0 -> "Client"
        1 -> S(R.string.client_mute)
        2 -> S(R.string.router)
        3 -> S(R.string.router_client)
        4 -> S(R.string.repeater)
        5 -> S(R.string.tracker)
        6 -> S(R.string.sensor)
        7 -> "TAK"
        8 -> S(R.string.client_hidden)
        9 -> S(R.string.lost_and_found)
        10 -> S(R.string.tak_tracker)
        11 -> S(R.string.router_late)
        12 -> S(R.string.client_base)
        else -> S(R.string.role, code)
    }

    fun nodeId(num: Long) = "!%08x".format(Locale.ROOT, num)

    /** HardwareModel enum (meshtastic/protobufs mesh.proto). */
    private val hwModels: Map<Int, String> by lazy {
        HW.split(',').associate { e -> val (k, v) = e.split(':'); k.toInt() to v }
    }

    /** A hardware model's enum name made readable: HELTEC_V3 → Heltec V3, T_ECHO → T-Echo. */
    fun hwModel(v: Int): String {
        val raw = hwModels[v] ?: return S(R.string.model_2, v)
        if (v == 255) return S(R.string.private_hardware)
        val words = raw.split('_')
        return buildString {
            words.forEachIndexed { i, w ->
                if (i > 0) append(if (words[i - 1].length == 1) '-' else ' ')
                append(if (w.any(Char::isDigit) || w.length <= 3) w else w.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) })
            }
        }
    }

    private const val HW = "1:TLORA_V2,2:TLORA_V1,3:TLORA_V2_1_1P6,4:TBEAM,5:HELTEC_V2_0,6:TBEAM_V0P7,7:T_ECHO,8:TLORA_V1_1P3," +
        "9:RAK4631,10:HELTEC_V2_1,11:HELTEC_V1,12:LILYGO_TBEAM_S3_CORE,13:RAK11200,14:NANO_G1,15:TLORA_V2_1_1P8,16:TLORA_T3_S3," +
        "17:NANO_G1_EXPLORER,18:NANO_G2_ULTRA,19:LORA_TYPE,20:WIPHONE,21:WIO_WM1110,22:RAK2560,23:HELTEC_HRU_3601," +
        "24:HELTEC_WIRELESS_BRIDGE,25:STATION_G1,26:RAK11310,27:MAKERFABS_TRACKER,28:MAKERFABS_RESERVED,29:CANARYONE," +
        "30:RP2040_LORA,31:STATION_G2,32:LORA_RELAY_V1,33:T_ECHO_PLUS,34:PPR,35:GENIEBLOCKS,36:NRF52_UNKNOWN,37:PORTDUINO," +
        "38:ANDROID_SIM,39:DIY_V1,40:NRF52840_PCA10059,41:DR_DEV,42:M5STACK,43:HELTEC_V3,44:HELTEC_WSL_V3,45:BETAFPV_2400_TX," +
        "46:BETAFPV_900_NANO_TX,47:RPI_PICO,48:HELTEC_WIRELESS_TRACKER,49:HELTEC_WIRELESS_PAPER,50:T_DECK,51:T_WATCH_S3," +
        "52:PICOMPUTER_S3,53:HELTEC_HT62,54:EBYTE_ESP32_S3,55:ESP32_S3_PICO,56:CHATTER_2,57:HELTEC_WIRELESS_PAPER_V1_0," +
        "58:HELTEC_WIRELESS_TRACKER_V1_0,59:UNPHONE,60:TD_LORAC,61:CDEBYTE_EORA_S3,62:TWC_MESH_V4,63:NRF52_PROMICRO_DIY," +
        "64:RADIOMASTER_900_BANDIT_NANO,65:HELTEC_CAPSULE_SENSOR_V3,66:HELTEC_VISION_MASTER_T190,67:HELTEC_VISION_MASTER_E213," +
        "68:HELTEC_VISION_MASTER_E290,69:HELTEC_MESH_NODE_T114,70:SENSECAP_INDICATOR,71:TRACKER_T1000_E,72:RAK3172,73:WIO_E5," +
        "74:RADIOMASTER_900_BANDIT,75:ME25LS01_4Y10TD,76:RP2040_FEATHER_RFM95,77:M5STACK_COREBASIC,78:M5STACK_CORE2,79:RPI_PICO2," +
        "80:M5STACK_CORES3,81:SEEED_XIAO_S3,82:MS24SF1,83:TLORA_C6,84:WISMESH_TAP,85:ROUTASTIC,86:MESH_TAB,87:MESHLINK," +
        "88:XIAO_NRF52_KIT,89:THINKNODE_M1,90:THINKNODE_M2,91:T_ETH_ELITE,92:HELTEC_SENSOR_HUB,93:MUZI_BASE,94:HELTEC_MESH_POCKET," +
        "95:SEEED_SOLAR_NODE,96:NOMADSTAR_METEOR_PRO,97:CROWPANEL,98:LINK_32,99:SEEED_WIO_TRACKER_L1,100:SEEED_WIO_TRACKER_L1_EINK," +
        "101:MUZI_R1_NEO,102:T_DECK_PRO,103:T_LORA_PAGER,104:M5STACK_RESERVED,105:WISMESH_TAG,106:RAK3312,107:THINKNODE_M5," +
        "108:HELTEC_MESH_SOLAR,109:T_ECHO_LITE,110:HELTEC_V4,111:M5STACK_C6L,112:M5STACK_CARDPUTER_ADV,113:HELTEC_WIRELESS_TRACKER_V2," +
        "114:T_WATCH_ULTRA,115:THINKNODE_M3,116:WISMESH_TAP_V2,117:RAK3401,118:RAK6421,119:THINKNODE_M4,120:THINKNODE_M6," +
        "121:MESHSTICK_1262,122:TBEAM_1_WATT,123:T5_S3_EPAPER_PRO,124:TBEAM_BPF,125:MINI_EPAPER_S3,126:TDISPLAY_S3_PRO," +
        "127:HELTEC_MESH_NODE_T096,128:MESH_TRACKER_X1,129:THINKNODE_M7,130:THINKNODE_M8,131:THINKNODE_M9,132:HELTEC_V4_R8," +
        "133:HELTEC_MESH_NODE_T1,134:STATION_G3,135:T_IMPULSE_PLUS,136:T_ECHO_CARD,137:SEEED_WIO_TRACKER_L2,138:CROWPANEL_P4," +
        "139:HELTEC_MESH_TOWER_V2,140:MESHNOLOGY_W10,141:HELTEC_RC32,142:HELTEC_RC52,143:HELTEC_RCC6,144:SEEED_WIO_TRACKER_L1_PRO_1W," +
        "145:MESHNOLOGY_W12,146:MESHPAGER_X2,147:T_CONNECT_PRO,148:AXIOMETA_GENESIS_MINI,149:MAKERFABS_NOMAD_TERMINAL,150:THINKNODE_MX,255:PRIVATE_HW"

    /** A channel to decode: its name (blank for the preset's name, as on the public channel) and its key. */
    class Channel(val name: String, val key: ByteArray?) {
        fun displayName(preset: MeshPreset) = name.ifBlank { preset.channelName }
        fun hash(preset: MeshPreset) = channelHash(displayName(preset), key)
    }

    enum class Status {
        /** Decrypted (or in clear) with one of the configured channels. */
        Decoded,
        /** A direct message encrypted with the recipients' public keys: only they can read it. */
        Private,
        /** From a channel none of the configured ones matches. */
        OtherChannel,
        /** The hash matches a configured channel but the content does not decode: same hash, different key. */
        WrongKey,
    }

    class Packet(
        val header: Header,
        val status: Status,
        val channel: String?,
        val data: Data?,
        val sizeBytes: Int,
    )

    /** Decodes a LoRa frame with the configured [channels]; null when it is too short to be a Meshtastic packet. */
    fun decode(frame: ByteArray, channels: List<Channel>, preset: MeshPreset): Packet? {
        val h = header(frame) ?: return null
        val body = frame.copyOfRange(16, frame.size)
        val candidates = channels.filter { it.hash(preset) == h.channel }
        for (c in candidates) {
            val plain = c.key?.let { crypt(it, h.from, h.id, body) } ?: body
            val d = data(plain) ?: continue
            return Packet(h, Status.Decoded, c.displayName(preset), d, frame.size)
        }
        val status = when {
            candidates.isNotEmpty() -> Status.WrongKey
            h.channel == 0 && h.to != BROADCAST -> Status.Private
            else -> Status.OtherChannel
        }
        return Packet(h, status, null, null, frame.size)
    }
}
