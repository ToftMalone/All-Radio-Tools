package com.allnetworktools.data.bitchat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * BitChat's Bluetooth mesh wire format, byte for byte the one used by the official iOS and Android apps
 * (permissionlesstech/bitchat): packet header, optional recipient / route / signature, DEFLATE payload
 * compression and PKCS#7 padding to 256/512/1024/2048 bytes.
 */
object Bitchat {
    /** GATT service and characteristic every BitChat device exposes (release build of the official app). */
    const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
    const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"

    const val TTL = 7
    const val TYPE_ANNOUNCE = 0x01
    const val TYPE_MESSAGE = 0x02
    const val TYPE_LEAVE = 0x03
    const val TYPE_NOISE_HANDSHAKE = 0x10
    const val TYPE_NOISE_ENCRYPTED = 0x11
    const val TYPE_FRAGMENT = 0x20
    const val TYPE_REQUEST_SYNC = 0x21

    val BROADCAST = ByteArray(8) { 0xFF.toByte() }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) {
            val v = if (2 * i + 2 <= s.length) s.substring(2 * i, 2 * i + 2).toIntOrNull(16) else null
            out[i] = (v ?: 0).toByte()
        }
        return out
    }

    /** A peer ID is the first 8 bytes of the SHA-256 of its Noise static public key. */
    fun peerId(noiseKey: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(noiseKey).copyOf(8))
}

class WirePayload(val bytes: ByteArray, val compressed: Boolean, val forPayload: ByteArray)

class Packet(
    val version: Int = 1,
    val type: Int,
    var ttl: Int,
    val timestamp: Long,
    val sender: ByteArray,
    val recipient: ByteArray? = null,
    val payload: ByteArray,
    var signature: ByteArray? = null,
    val route: List<ByteArray>? = null,
    val wire: WirePayload? = null,
) {
    val senderId: String get() = Bitchat.hex(sender)
    val recipientId: String? get() = recipient?.let { Bitchat.hex(it) }
    val isBroadcast: Boolean get() = recipient == null || recipient.contentEquals(Bitchat.BROADCAST)

    fun copy(ttl: Int = this.ttl, signature: ByteArray? = this.signature, payload: ByteArray = this.payload, wire: WirePayload? = this.wire) =
        Packet(version, type, ttl, timestamp, sender, recipient, payload, signature, route, wire)

    /** What gets signed: the packet without its signature and with TTL 0, as every other client builds it. */
    fun signingBytes(): ByteArray? = Packet(version, type, 0, timestamp, sender, recipient, payload, null, route, wire).encode()

    /** Dedup key shared with the official app: SHA-256(type ‖ sender ‖ timestamp ‖ payload) truncated to 16 bytes. */
    fun id(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(type.toByte()); md.update(sender)
        md.update(ByteBuffer.allocate(8).putLong(timestamp).array())
        md.update(payload)
        return Bitchat.hex(md.digest().copyOf(16))
    }

    fun encode(padding: Boolean = true): ByteArray? = BinaryProtocol.encode(this, padding)
}

object BinaryProtocol {
    private const val HEADER_V1 = 14
    private const val HEADER_V2 = 16
    private const val ID = 8
    private const val SIG = 64
    private const val MAX_PAYLOAD = 10_485_760
    private const val F_RECIPIENT = 0x01
    private const val F_SIGNATURE = 0x02
    private const val F_COMPRESSED = 0x04
    private const val F_ROUTE = 0x08

    fun encode(p: Packet, padding: Boolean = true): ByteArray? {
        if (p.payload.size > MAX_PAYLOAD) return null
        var payload = p.payload
        var original = 0
        var compressed = false
        val w = p.wire
        if (w != null && w.forPayload.contentEquals(p.payload)) {
            if (w.compressed) { payload = w.bytes; original = p.payload.size; compressed = true }
        } else if (Compression.should(payload)) {
            Compression.deflate(payload)?.let { original = payload.size; payload = it; compressed = true }
        }
        val v2 = p.version >= 2
        val lenField = if (compressed) (if (v2) 4 else 2) else 0
        val dataSize = payload.size + lenField
        if (!v2 && (dataSize > 0xFFFF || original > 0xFFFF)) return null
        val route = if (v2) p.route?.takeIf { it.isNotEmpty() } else null
        val buf = ByteBuffer.allocate(
            (if (v2) HEADER_V2 else HEADER_V1) + ID + (if (p.recipient != null) ID else 0) + dataSize +
                (if (p.signature != null) SIG else 0) + (if (route != null) 1 + route.size * ID else 0) + 16,
        ).order(ByteOrder.BIG_ENDIAN)
        buf.put(p.version.toByte()).put(p.type.toByte()).put(p.ttl.toByte()).putLong(p.timestamp)
        var flags = 0
        if (p.recipient != null) flags = flags or F_RECIPIENT
        if (p.signature != null) flags = flags or F_SIGNATURE
        if (compressed) flags = flags or F_COMPRESSED
        if (route != null) flags = flags or F_ROUTE
        buf.put(flags.toByte())
        if (v2) buf.putInt(dataSize) else buf.putShort(dataSize.toShort())
        buf.put(p.sender.copyOf(ID))
        p.recipient?.let { buf.put(it.copyOf(ID)) }
        if (route != null) { buf.put(route.size.coerceAtMost(255).toByte()); route.take(255).forEach { buf.put(it.copyOf(ID)) } }
        if (compressed) { if (v2) buf.putInt(original) else buf.putShort(original.toShort()) }
        buf.put(payload)
        p.signature?.let { buf.put(it.copyOf(SIG)) }
        val out = buf.array().copyOf(buf.position())
        return if (padding) Padding.pad(out, Padding.optimalBlock(out.size)) else out
    }

    fun decode(data: ByteArray): Packet? {
        decodeCore(data)?.let { return it }
        val unpadded = Padding.unpad(data)
        if (unpadded.contentEquals(data)) return null
        return decodeCore(unpadded)
    }

    private fun decodeCore(raw: ByteArray): Packet? = try {
        if (raw.size < HEADER_V1 + ID) null else {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get().toInt() and 0xFF
            if (version != 1 && version != 2) return null
            val v2 = version == 2
            val type = buf.get().toInt() and 0xFF
            val ttl = buf.get().toInt() and 0xFF
            val ts = buf.getLong()
            val flags = buf.get().toInt() and 0xFF
            val hasRecipient = flags and F_RECIPIENT != 0
            val hasSig = flags and F_SIGNATURE != 0
            val isCompressed = flags and F_COMPRESSED != 0
            val hasRoute = v2 && flags and F_ROUTE != 0
            val length = if (v2) buf.getInt() else buf.getShort().toInt() and 0xFFFF
            if (length < 0 || length > MAX_PAYLOAD) return null
            var expected = (if (v2) HEADER_V2 else HEADER_V1) + ID + length
            if (hasRecipient) expected += ID
            if (hasRoute) {
                val at = buf.position() + ID + (if (hasRecipient) ID else 0)
                if (raw.size < at + 1) return null
                expected += 1 + (raw[at].toInt() and 0xFF) * ID
            }
            if (hasSig) expected += SIG
            if (raw.size < expected) return null
            val sender = ByteArray(ID).also { buf.get(it) }
            val recipient = if (hasRecipient) ByteArray(ID).also { buf.get(it) } else null
            val route = if (hasRoute) {
                val n = buf.get().toInt() and 0xFF
                if (n == 0) null else List(n) { ByteArray(ID).also { h -> buf.get(h) } }
            } else null
            var wireBytes: ByteArray? = null
            val payload = if (isCompressed) {
                val lf = if (v2) 4 else 2
                if (length < lf) return null
                val orig = if (v2) buf.getInt() else buf.getShort().toInt() and 0xFFFF
                val csize = length - lf
                if (orig <= 0 || orig > MAX_PAYLOAD || csize <= 0 || orig.toDouble() / csize > 50_000.0) return null
                val comp = ByteArray(csize).also { buf.get(it) }
                wireBytes = comp
                Compression.inflate(comp, orig) ?: return null
            } else ByteArray(length).also { buf.get(it) }
            val sig = if (hasSig) ByteArray(SIG).also { buf.get(it) } else null
            Packet(version, type, ttl, ts, sender, recipient, payload, sig, route, WirePayload(wireBytes ?: payload, wireBytes != null, payload))
        }
    } catch (_: Exception) {
        null
    }
}

object Padding {
    private val blocks = intArrayOf(256, 512, 1024, 2048)

    fun optimalBlock(size: Int): Int = blocks.firstOrNull { size + 16 <= it } ?: size

    fun pad(data: ByteArray, target: Int): ByteArray {
        if (data.size >= target) return data
        val need = target - data.size
        if (need > 255) return data
        return data.copyOf(target).also { for (i in data.size until target) it[i] = need.toByte() }
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val last = data.last()
        val n = last.toInt() and 0xFF
        if (n <= 0 || n > data.size) return data
        for (i in data.size - n until data.size) if (data[i] != last) return data
        return data.copyOf(data.size - n)
    }
}

object Compression {
    private const val THRESHOLD = 100

    fun should(data: ByteArray): Boolean {
        if (data.size < THRESHOLD) return false
        val distinct = data.toSet().size
        return distinct.toDouble() / minOf(data.size, 256) < 0.9
    }

    fun deflate(data: ByteArray): ByteArray? {
        if (data.size < THRESHOLD) return null
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            out.toByteArray().takeIf { it.isNotEmpty() && it.size < data.size }
        } finally {
            d.end()
        }
    }

    /** Raw DEFLATE first (iOS and Android), zlib-wrapped as a fallback. */
    fun inflate(data: ByteArray, size: Int): ByteArray? = run(data, size, true) ?: run(data, size, false)

    private fun run(data: ByteArray, size: Int, nowrap: Boolean): ByteArray? {
        val inf = Inflater(nowrap)
        return try {
            inf.setInput(data)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inf.finished()) {
                val r = inf.inflate(out, n, size - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            out.takeIf { n == size }
        } catch (_: Exception) {
            null
        } finally {
            inf.end()
        }
    }
}

/** The announcement carried by ANNOUNCE packets: nickname + identity keys (+ direct neighbours). */
class Announcement(val nickname: String, val noiseKey: ByteArray, val signingKey: ByteArray, val neighbors: List<String>?) {
    fun encode(): ByteArray? {
        val nick = nickname.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
        val out = ByteArrayOutputStream()
        fun tlv(t: Int, v: ByteArray) { out.write(t); out.write(v.size); out.write(v) }
        tlv(0x01, nick); tlv(0x02, noiseKey); tlv(0x03, signingKey)
        neighbors?.take(10)?.let { n -> tlv(0x04, n.fold(ByteArray(0)) { a, id -> a + Bitchat.unhex(id) }) }
        return out.toByteArray()
    }

    companion object {
        fun decode(d: ByteArray): Announcement? {
            var o = 0
            var nick: String? = null
            var noise: ByteArray? = null
            var sign: ByteArray? = null
            var neigh: List<String>? = null
            while (o + 2 <= d.size) {
                val t = d[o].toInt() and 0xFF
                val l = d[o + 1].toInt() and 0xFF
                o += 2
                if (o + l > d.size) return null
                val v = d.copyOfRange(o, o + l)
                o += l
                when (t) {
                    0x01 -> nick = String(v, Charsets.UTF_8)
                    0x02 -> noise = v
                    0x03 -> sign = v
                    0x04 -> neigh = (0 until v.size / 8).map { Bitchat.hex(v.copyOfRange(it * 8, it * 8 + 8)) }
                }
            }
            return if (nick != null && noise != null && sign != null) Announcement(nick, noise, sign, neigh) else null
        }
    }
}

/** Payload inside a Noise-encrypted private packet: one type byte then the data. */
object NoisePayload {
    const val PRIVATE_MESSAGE = 0x01
    const val READ_RECEIPT = 0x02
    const val DELIVERED = 0x03

    fun privateMessage(id: String, text: String): ByteArray? {
        val i = id.toByteArray(Charsets.UTF_8)
        val c = text.toByteArray(Charsets.UTF_8)
        if (i.size > 255 || c.size > 255) return null
        return byteArrayOf(PRIVATE_MESSAGE.toByte(), 0, i.size.toByte()) + i + byteArrayOf(1, c.size.toByte()) + c
    }

    fun ack(type: Int, id: String): ByteArray = byteArrayOf(type.toByte()) + id.toByteArray(Charsets.UTF_8)

    /** (messageId, content) of a PRIVATE_MESSAGE body. */
    fun parsePrivate(d: ByteArray): Pair<String, String>? {
        var o = 0
        var id: String? = null
        var text: String? = null
        while (o + 2 <= d.size) {
            val t = d[o].toInt() and 0xFF
            val l = d[o + 1].toInt() and 0xFF
            o += 2
            if (o + l > d.size) return null
            val v = String(d, o, l, Charsets.UTF_8)
            o += l
            when (t) { 0 -> id = v; 1 -> text = v; else -> return null }
        }
        return if (id != null && text != null) id to text else null
    }
}

/** Fragment payload: id(8) ‖ index(2) ‖ total(2) ‖ original type(1) ‖ data. */
object Fragments {
    private const val HEADER = 13
    private const val MAX_DATA = 469
    private val rng = SecureRandom()

    fun split(p: Packet): List<Packet> {
        val whole = p.encode(padding = false) ?: return emptyList()
        if (whole.size <= 512) return listOf(p)
        val overhead = 13 + 8 + (if (p.recipient != null) 8 else 0) + HEADER + 16
        val chunk = (512 - overhead).coerceAtMost(MAX_DATA)
        val id = ByteArray(8).also { rng.nextBytes(it) }
        val parts = (whole.size + chunk - 1) / chunk
        return (0 until parts).map { i ->
            val data = whole.copyOfRange(i * chunk, minOf(whole.size, (i + 1) * chunk))
            val pl = ByteBuffer.allocate(HEADER + data.size).put(id).putShort(i.toShort()).putShort(parts.toShort()).put(p.type.toByte()).put(data).array()
            Packet(1, Bitchat.TYPE_FRAGMENT, p.ttl, p.timestamp, p.sender, p.recipient, pl)
        }
    }
}

/** Collects fragments until a packet is whole again; sets older than 30 s are dropped. */
class Reassembler {
    private class Set(val total: Int, val type: Int, val at: Long, val parts: HashMap<Int, ByteArray> = HashMap(), var size: Int = 0)

    private val sets = HashMap<String, Set>()

    @Synchronized
    fun add(p: Packet, now: Long = System.currentTimeMillis()): Packet? {
        sets.entries.removeAll { now - it.value.at > 30_000 }
        val d = p.payload
        if (d.size < 13) return null
        val b = ByteBuffer.wrap(d)
        val id = Bitchat.hex(d.copyOf(8))
        b.position(8)
        val index = b.getShort().toInt() and 0xFFFF
        val total = b.getShort().toInt() and 0xFFFF
        val type = b.get().toInt() and 0xFF
        if (total == 0 || total > 256 || index >= total) return null
        val set = sets.getOrPut(id) { if (sets.size >= 64) return null else Set(total, type, now) }
        if (set.total != total || set.type != type) { sets.remove(id); return null }
        if (set.parts.containsKey(index)) return null
        val data = d.copyOfRange(13, d.size)
        if (set.size + data.size > 1_048_576) { sets.remove(id); return null }
        set.parts[index] = data
        set.size += data.size
        if (set.parts.size < total) return null
        sets.remove(id)
        val whole = ByteArrayOutputStream()
        for (i in 0 until total) whole.write(set.parts[i] ?: return null)
        return BinaryProtocol.decode(whole.toByteArray())
    }
}
