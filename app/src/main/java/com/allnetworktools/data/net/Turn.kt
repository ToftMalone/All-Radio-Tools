package com.allnetworktools.data.net

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** STUN message codec (RFC 8489) with the TURN attributes (RFC 8656) a relay client needs. */
object Stun {
    const val MAGIC = 0x2112A442

    const val ALLOCATE = 0x003
    const val REFRESH = 0x004
    const val SEND = 0x006
    const val DATA = 0x007
    const val CREATE_PERMISSION = 0x008

    const val REQUEST = 0x0000
    const val INDICATION = 0x0010
    const val SUCCESS = 0x0100
    const val ERROR = 0x0110

    const val A_USERNAME = 0x0006
    const val A_MESSAGE_INTEGRITY = 0x0008
    const val A_ERROR_CODE = 0x0009
    const val A_LIFETIME = 0x000D
    const val A_XOR_PEER_ADDRESS = 0x0012
    const val A_DATA = 0x0013
    const val A_REALM = 0x0014
    const val A_NONCE = 0x0015
    const val A_XOR_RELAYED_ADDRESS = 0x0016
    const val A_REQUESTED_TRANSPORT = 0x0019
    const val A_FINGERPRINT = 0x8028

    class Message(val type: Int, val tid: ByteArray, val attrs: List<Pair<Int, ByteArray>>) {
        val method get() = type and 0x3EEF
        val cls get() = type and 0x0110
        operator fun get(attr: Int): ByteArray? = attrs.firstOrNull { it.first == attr }?.second
        val errorCode: Int? get() = get(A_ERROR_CODE)?.takeIf { it.size >= 4 }?.let { (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xFF) }
    }

    fun longTermKey(user: String, realm: String, pass: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$user:$realm:$pass".toByteArray(Charsets.UTF_8))

    /** Attributes are padded to 4 bytes; with [key] a MESSAGE-INTEGRITY follows them, then a FINGERPRINT. */
    fun encode(type: Int, tid: ByteArray, attrs: List<Pair<Int, ByteArray>>, key: ByteArray? = null, fingerprint: Boolean = true): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        attrs.forEach { (t, v) -> body.write(attr(t, v)) }
        var msg = header(type, body.size() + (if (key != null) 24 else 0), tid) + body.toByteArray()
        if (key != null) {
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }.doFinal(msg)
            msg += attr(A_MESSAGE_INTEGRITY, mac)
        }
        if (fingerprint) {
            msg = header(type, msg.size - 20 + 8, tid) + msg.copyOfRange(20, msg.size)
            val crc = CRC32().apply { update(msg) }.value.toInt() xor 0x5354554E
            msg += attr(A_FINGERPRINT, ByteBuffer.allocate(4).putInt(crc).array())
        }
        return msg
    }

    private fun header(type: Int, length: Int, tid: ByteArray) =
        ByteBuffer.allocate(20).putShort(type.toShort()).putShort(length.toShort()).putInt(MAGIC).put(tid).array()

    private fun attr(t: Int, v: ByteArray): ByteArray {
        val padded = (v.size + 3) / 4 * 4
        return ByteBuffer.allocate(4 + padded).putShort(t.toShort()).putShort(v.size.toShort()).put(v).array()
    }

    fun decode(b: ByteArray, len: Int = b.size): Message? {
        if (len < 20 || (b[0].toInt() and 0xC0) != 0) return null
        val bb = ByteBuffer.wrap(b, 0, len)
        val type = bb.short.toInt() and 0xFFFF
        val length = bb.short.toInt() and 0xFFFF
        if (bb.int != MAGIC || 20 + length > len) return null
        val tid = ByteArray(12).also { bb.get(it) }
        val attrs = mutableListOf<Pair<Int, ByteArray>>()
        var p = 20
        while (p + 4 <= 20 + length) {
            val t = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            val l = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (p + 4 + l > 20 + length) return null
            attrs += t to b.copyOfRange(p + 4, p + 4 + l)
            p += 4 + (l + 3) / 4 * 4
        }
        return Message(type, tid, attrs)
    }

    fun xorAddress(addr: InetSocketAddress, tid: ByteArray): ByteArray {
        val ip = addr.address.address
        val mask = ByteBuffer.allocate(16).putInt(MAGIC).put(tid).array()
        val out = ByteBuffer.allocate(4 + ip.size)
        out.put(0).put(if (ip.size == 4) 1 else 2).putShort((addr.port xor (MAGIC ushr 16)).toShort())
        ip.forEachIndexed { i, x -> out.put((x.toInt() xor mask[i].toInt()).toByte()) }
        return out.array()
    }

    fun xorAddress(v: ByteArray, tid: ByteArray): InetSocketAddress {
        val mask = ByteBuffer.allocate(16).putInt(MAGIC).put(tid).array()
        val port = (((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)) xor (MAGIC ushr 16)
        val ip = ByteArray(if (v[1].toInt() == 1) 4 else 16) { (v[4 + it].toInt() xor mask[it].toInt()).toByte() }
        return InetSocketAddress(InetAddress.getByAddress(ip), port)
    }
}

/**
 * Minimal TURN client over UDP with long-term credentials: one allocation, permissions, and data sent and received
 * through Send and Data indications. Blocking; callers run it on an I/O thread.
 */
class TurnClient(private val server: InetSocketAddress, private val user: String, private val pass: String) : Closeable {
    private val socket = DatagramSocket().apply { soTimeout = RECEIVE_TICK_MS }
    private val buf = ByteArray(2048)
    private var realm: ByteArray? = null
    private var nonce: ByteArray? = null
    private var key: ByteArray? = null
    var relayed: InetSocketAddress? = null
        private set

    fun allocate(deadline: Long): InetSocketAddress {
        val r = transact(Stun.ALLOCATE, deadline) { listOf(Stun.A_REQUESTED_TRANSPORT to byteArrayOf(17, 0, 0, 0)) }
        val v = r[Stun.A_XOR_RELAYED_ADDRESS] ?: throw IOException("TURN: no relayed address")
        return Stun.xorAddress(v, r.tid).also { relayed = it }
    }

    fun permit(peer: InetAddress, deadline: Long) {
        transact(Stun.CREATE_PERMISSION, deadline) { tid -> listOf(Stun.A_XOR_PEER_ADDRESS to Stun.xorAddress(InetSocketAddress(peer, 0), tid)) }
    }

    fun send(peer: InetSocketAddress, data: ByteArray) {
        val tid = newTid()
        val msg = Stun.encode(Stun.SEND or Stun.INDICATION, tid, listOf(Stun.A_XOR_PEER_ADDRESS to Stun.xorAddress(peer, tid), Stun.A_DATA to data))
        socket.send(DatagramPacket(msg, msg.size, server))
    }

    /** The payload of the next Data indication, or null after [RECEIVE_TICK_MS] without one. */
    fun receive(): ByteArray? {
        val m = next() ?: return null
        return if (m.method == Stun.DATA && m.cls == Stun.INDICATION) m[Stun.A_DATA] else null
    }

    override fun close() {
        if (relayed != null && key != null) runCatching {
            val msg = Stun.encode(Stun.REFRESH or Stun.REQUEST, newTid(), listOf(Stun.A_LIFETIME to byteArrayOf(0, 0, 0, 0)) + auth(), key)
            socket.send(DatagramPacket(msg, msg.size, server))
        }
        socket.close()
    }

    private fun auth(): List<Pair<Int, ByteArray>> =
        if (key == null) emptyList() else listOf(Stun.A_USERNAME to user.toByteArray(), Stun.A_REALM to realm!!, Stun.A_NONCE to nonce!!)

    private fun next(): Stun.Message? = try {
        val p = DatagramPacket(buf, buf.size)
        socket.receive(p)
        if (p.address == server.address && p.port == server.port) Stun.decode(buf, p.length) else null
    } catch (_: SocketTimeoutException) {
        null
    }

    /** One request with retransmissions (RTO 250 ms, doubling), re-sent with credentials after a 401 or a stale nonce. */
    private fun transact(method: Int, deadline: Long, attrs: (tid: ByteArray) -> List<Pair<Int, ByteArray>>): Stun.Message {
        repeat(4) {
            val tid = newTid()
            val signed = key != null
            val msg = Stun.encode(method or Stun.REQUEST, tid, attrs(tid) + auth(), key)
            var rto = 250L
            var resend = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) throw SocketTimeoutException("TURN: no answer")
                if (now >= resend) {
                    socket.send(DatagramPacket(msg, msg.size, server))
                    resend = now + rto
                    rto *= 2
                }
                val r = next() ?: continue
                if (!r.tid.contentEquals(tid)) continue
                if (r.cls == Stun.SUCCESS) return r
                val code = r.errorCode
                if (code == 438 || (code == 401 && !signed)) {
                    r[Stun.A_REALM]?.let { realm = it }
                    nonce = r[Stun.A_NONCE] ?: throw IOException("TURN: no nonce")
                    key = Stun.longTermKey(user, String(realm ?: throw IOException("TURN: no realm")), pass)
                    break
                }
                if (code == 401) throw IOException("TURN: credentials refused")
                throw IOException("TURN: error $code")
            }
        }
        throw IOException("TURN: authentication failed")
    }

    private fun newTid() = ByteArray(12).also { random.nextBytes(it) }

    companion object {
        const val RECEIVE_TICK_MS = 100
        private val random = SecureRandom()

        /** "host:port" resolved, IPv4 first: the allocation asks for an IPv4 relay. */
        fun resolve(uri: String): InetSocketAddress {
            val host = uri.substringBeforeLast(':')
            val port = uri.substringAfterLast(':').toIntOrNull() ?: 3478
            val all = InetAddress.getAllByName(host)
            return InetSocketAddress(all.firstOrNull { it is Inet4Address } ?: all.first(), port)
        }
    }
}
