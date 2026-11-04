package com.allnetworktools.data.sdr

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCore over the air (meshcore-dev/MeshCore, docs/packet_format.md and docs/payloads.md, firmware 1.12):
 *
 * `[header][transport codes, optional][path length][path][payload]`. The header gives the route (flood or direct, with
 * or without transport codes), the payload type and its version; the path lists the repeaters a flood went through (1 to
 * 3 bytes of each one's public key).
 *
 * In the clear: node adverts (public key, time, signature, then type, position and name), acks, traces, the routing of
 * every packet. Group messages are AES-128-ECB with a 2-byte HMAC-SHA256: the Public channel's key is published, and a
 * "#name" channel's key is the start of SHA-256 of its name, so both can be read. Direct messages are encrypted for
 * their recipient and stay so.
 */
object MeshCore {
    const val SYNC_WORD = 0x12

    const val ROUTE_TRANSPORT_FLOOD = 0
    const val ROUTE_FLOOD = 1
    const val ROUTE_DIRECT = 2
    const val ROUTE_TRANSPORT_DIRECT = 3

    const val TYPE_REQ = 0
    const val TYPE_RESPONSE = 1
    const val TYPE_TXT_MSG = 2
    const val TYPE_ACK = 3
    const val TYPE_ADVERT = 4
    const val TYPE_GRP_TXT = 5
    const val TYPE_GRP_DATA = 6
    const val TYPE_ANON_REQ = 7
    const val TYPE_PATH = 8
    const val TYPE_TRACE = 9
    const val TYPE_MULTIPART = 10
    const val TYPE_CONTROL = 11
    const val TYPE_RAW_CUSTOM = 15

    private val typeNames = mapOf(
        0 to "REQ", 1 to "RESPONSE", 2 to "TXT_MSG", 3 to "ACK", 4 to "ADVERT", 5 to "GRP_TXT", 6 to "GRP_DATA", 7 to "ANON_REQ",
        8 to "PATH", 9 to "TRACE", 10 to "MULTIPART", 11 to "CONTROL", 15 to "RAW_CUSTOM",
    )

    fun typeName(t: Int) = typeNames[t] ?: "TYPE_$t"

    private const val MAX_PATH = 64
    private const val MAX_PAYLOAD = 184

    class Packet(
        val raw: ByteArray,
        val route: Int,
        val type: Int,
        val version: Int,
        val transportCodes: IntArray?,
        /** Bytes of each repeater's id in [path] (1 to 3). */
        val hashSize: Int,
        val path: List<ByteArray>,
        val payload: ByteArray,
    ) {
        val flood get() = route == ROUTE_FLOOD || route == ROUTE_TRANSPORT_FLOOD
    }

    /** Null when [raw] cannot be a MeshCore packet (unknown version, path or payload out of bounds). */
    fun parse(raw: ByteArray): Packet? {
        if (raw.size < 3) return null
        var i = 0
        val h = raw[i++].toInt() and 0xFF
        val route = h and 3
        val type = (h shr 2) and 15
        val ver = h shr 6
        if (ver != 0 || type in 12..14) return null
        var codes: IntArray? = null
        if (route == ROUTE_TRANSPORT_FLOOD || route == ROUTE_TRANSPORT_DIRECT) {
            if (raw.size < i + 4) return null
            codes = intArrayOf(u16(raw, i), u16(raw, i + 2)); i += 4
        }
        if (i >= raw.size) return null
        val pl = raw[i++].toInt() and 0xFF
        val size = (pl shr 6) + 1
        val count = pl and 63
        if (size == 4 || count * size > MAX_PATH || i + count * size >= raw.size) return null
        val path = List(count) { k -> raw.copyOfRange(i + k * size, i + (k + 1) * size) }
        i += count * size
        val payload = raw.copyOfRange(i, raw.size)
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD) return null
        return Packet(raw, route, type, ver, codes, size, path, payload)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---- adverts ----------------------------------------------------------------------------------------

    const val NODE_CHAT = 1
    const val NODE_REPEATER = 2
    const val NODE_ROOM = 3
    const val NODE_SENSOR = 4

    class Advert(
        val publicKey: ByteArray,
        val timestampS: Long,
        val signature: ByteArray,
        val appData: ByteArray,
        val nodeType: Int,
        val lat: Double?,
        val lon: Double?,
        val name: String?,
    ) {
        val keyHex get() = hex(publicKey)
        /** The first byte of the key: the id repeaters write in paths. */
        val hash get() = publicKey[0].toInt() and 0xFF
    }

    fun advert(p: ByteArray): Advert? {
        if (p.size < 32 + 4 + 64 + 1) return null
        val key = p.copyOfRange(0, 32)
        val ts = le32(p, 32).toLong() and 0xFFFFFFFFL
        val sig = p.copyOfRange(36, 100)
        val app = p.copyOfRange(100, p.size)
        val flags = app[0].toInt() and 0xFF
        var i = 1
        var lat: Double? = null
        var lon: Double? = null
        if (flags and 0x10 != 0) {
            if (app.size < i + 8) return null
            val la = le32(app, i); val lo = le32(app, i + 4)
            i += 8
            if (la in -90_000_000..90_000_000 && lo in -180_000_000..180_000_000 && (la != 0 || lo != 0)) { lat = la / 1e6; lon = lo / 1e6 }
        }
        if (flags and 0x20 != 0) i += 2
        if (flags and 0x40 != 0) i += 2
        if (i > app.size) return null
        val name = if (flags and 0x80 != 0 && i < app.size) String(app, i, app.size - i, Charsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() } else null
        return Advert(key, ts, sig, app, flags and 15, lat, lon, name)
    }

    /**
     * Checks an advert's Ed25519 signature over key, time and app data. Null when this Android has no Ed25519 (before
     * Android 13), so the advert is shown unverified rather than rejected.
     */
    fun verify(a: Advert): Boolean? = runCatching {
        val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        val pub = java.security.KeyFactory.getInstance("Ed25519").generatePublic(java.security.spec.X509EncodedKeySpec(prefix + a.publicKey))
        val s = java.security.Signature.getInstance("Ed25519")
        s.initVerify(pub)
        s.update(a.publicKey)
        s.update(byteArrayOf((a.timestampS).toByte(), (a.timestampS shr 8).toByte(), (a.timestampS shr 16).toByte(), (a.timestampS shr 24).toByte()))
        s.update(a.appData)
        s.verify(a.signature)
    }.getOrNull()

    // ---- group channels ---------------------------------------------------------------------------------

    /** A group channel whose key is known: the Public one, or a "#name" one. */
    class Channel(val name: String, val secret: ByteArray) {
        val hash: Int = (MessageDigest.getInstance("SHA-256").digest(secret)[0].toInt() and 0xFF)
    }

    /** The Public channel every companion app joins (its key is in the firmware's sources). */
    val PUBLIC = Channel("Public", "8b3387e9c5cdea6ac9e5edbaa115cd72".chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    /** A hashtag channel: its key is the first 16 bytes of SHA-256 of its name with the '#'. */
    fun hashtag(name: String): Channel {
        val n = "#" + name.trim().removePrefix("#")
        return Channel(n, MessageDigest.getInstance("SHA-256").digest(n.toByteArray(Charsets.UTF_8)).copyOf(16))
    }

    class GroupText(val channel: Channel, val timestampS: Long, val sender: String?, val text: String)

    /** Opens a GRP_TXT or GRP_DATA payload with the first channel whose hash and MAC match; null if none does. */
    fun openGroup(p: ByteArray, channels: List<Channel>): Pair<Channel, ByteArray>? {
        if (p.size < 1 + 2 + 16) return null
        val ct = p.copyOfRange(3, p.size)
        if (ct.size % 16 != 0) return null
        for (c in channels) {
            if (c.hash != (p[0].toInt() and 0xFF)) continue
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(c.secret.copyOf(32), "HmacSHA256"))
            val m = mac.doFinal(ct)
            if (m[0] != p[1] || m[1] != p[2]) continue
            val aes = Cipher.getInstance("AES/ECB/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(c.secret, "AES"))
            return c to aes.doFinal(ct)
        }
        return null
    }

    /** A group text: 4 bytes of time, a type byte (0 for plain text), then "sender: message", zero-padded. */
    fun groupText(p: ByteArray, channels: List<Channel>): GroupText? {
        val (c, d) = openGroup(p, channels) ?: return null
        if (d.size < 5 || (d[4].toInt() and 0xFF) shr 2 != 0) return null
        var end = d.size
        for (k in 5 until d.size) if (d[k].toInt() == 0) { end = k; break }
        val s = String(d, 5, end - 5, Charsets.UTF_8)
        val colon = s.indexOf(": ")
        val sender = if (colon in 1..32) s.substring(0, colon) else null
        return GroupText(c, le32(d, 0).toLong() and 0xFFFFFFFFL, sender, if (sender != null) s.substring(colon + 2) else s)
    }

    /** Destination and source ids of an encrypted message (REQ, RESPONSE, TXT_MSG, PATH), or the destination of ANON_REQ. */
    fun addressees(type: Int, p: ByteArray): Pair<Int, Int?>? = when (type) {
        TYPE_REQ, TYPE_RESPONSE, TYPE_TXT_MSG, TYPE_PATH -> if (p.size >= 2) (p[0].toInt() and 0xFF) to (p[1].toInt() and 0xFF) else null
        TYPE_ANON_REQ -> if (p.size >= 1 + 32) (p[0].toInt() and 0xFF) to null else null
        else -> null
    }

    // ---- band -------------------------------------------------------------------------------------------

    /**
     * Europe: the firmware's default 869.618 MHz at 62.5 kHz (SF8 by default, SF7 to SF12 heard), and the older wide
     * settings on 869.525 MHz at 250 kHz (SF10 and SF11).
     */
    val EU868 = MeshBand(
        869_025_000L, wide = false,
        listOf(
            MeshChannel(869_618_000L, 62_500.0, listOf(7, 8, 9, 10, 11, 12)),
            MeshChannel(869_525_000L, 250_000.0, listOf(10, 11)),
        ),
    )
}
