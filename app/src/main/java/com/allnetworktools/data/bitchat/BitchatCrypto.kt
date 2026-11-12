package com.allnetworktools.data.bitchat

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A device's long-term identity: X25519 key for Noise sessions, Ed25519 key for packet signatures. */
class Identity(val noisePrivate: ByteArray, val signingPrivate: ByteArray) {
    val noisePublic: ByteArray = ByteArray(32).also { X25519.scalarMultBase(noisePrivate, 0, it, 0) }
    val signingPublic: ByteArray = Ed25519PrivateKeyParameters(signingPrivate, 0).generatePublicKey().encoded
    val peerId: String = Bitchat.peerId(noisePublic)

    fun sign(data: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, Ed25519PrivateKeyParameters(signingPrivate, 0))
        update(data, 0, data.size)
        generateSignature()
    }

    companion object {
        fun generate(rng: SecureRandom = SecureRandom()) = Identity(ByteArray(32).also { rng.nextBytes(it) }, ByteArray(32).also { rng.nextBytes(it) })

        fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            Ed25519Signer().run {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(data, 0, data.size)
                verifySignature(signature)
            }
        } catch (_: Exception) {
            false
        }
    }
}

/** `Noise_XX_25519_ChaChaPoly_SHA256` with an empty prologue, the only pattern BitChat uses for private chats. */
class NoiseSession(private val local: Identity, private val initiator: Boolean, private val rng: SecureRandom = SecureRandom()) {
    private val sha = MessageDigest.getInstance("SHA-256")
    private var ck = ByteArray(32)
    private var h = ByteArray(32)
    private var k: ByteArray? = null
    private var n = 0L
    private var e: ByteArray? = null
    private var re: ByteArray? = null
    private var step = 0
    var remoteStatic: ByteArray? = null
        private set
    val startedAt = System.currentTimeMillis()

    private var send: ByteArray? = null
    private var recv: ByteArray? = null
    private var sent = 0L
    private var highest = -1L
    private val seen = HashSet<Long>()

    val established get() = send != null
    val isInitiator get() = initiator

    init {
        val name = "Noise_XX_25519_ChaChaPoly_SHA256".toByteArray()
        h = name.copyOf(32)
        ck = h.copyOf()
        mixHash(ByteArray(0))
    }

    // ---- handshake ------------------------------------------------------------------------------

    /** Initiator: message 1 (`e`). Responder: call [read] with it first. */
    fun start(): ByteArray {
        check(initiator && step == 0)
        val priv = ByteArray(32).also { rng.nextBytes(it) }
        e = priv
        val pub = pub(priv)
        mixHash(pub)
        mixHash(ByteArray(0))
        step = 1
        return pub
    }

    /** Feeds the peer's handshake message and returns the reply to send, or null when none is due. */
    fun read(msg: ByteArray): ByteArray? {
        if (initiator) {
            check(step == 1) { "unexpected message" }
            require(msg.size == 96)
            val r = msg.copyOfRange(0, 32)
            re = r
            mixHash(r)
            mixKey(dh(e!!, r))
            val rs = decryptAndHash(msg.copyOfRange(32, 80))
            remoteStatic = rs
            mixKey(dh(e!!, rs))
            decryptAndHash(msg.copyOfRange(80, 96))
            val s = encryptAndHash(local.noisePublic)
            mixKey(dh(local.noisePrivate, r))
            val tail = encryptAndHash(ByteArray(0))
            split(true)
            step = 3
            return s + tail
        }
        when (step) {
            0 -> {
                require(msg.size == 32)
                val r = msg.copyOf()
                re = r
                mixHash(r)
                mixHash(ByteArray(0))
                val priv = ByteArray(32).also { rng.nextBytes(it) }
                e = priv
                val ep = pub(priv)
                mixHash(ep)
                mixKey(dh(priv, r))
                val s = encryptAndHash(local.noisePublic)
                mixKey(dh(local.noisePrivate, r))
                val tail = encryptAndHash(ByteArray(0))
                step = 2
                return ep + s + tail
            }
            2 -> {
                require(msg.size == 64)
                val rs = decryptAndHash(msg.copyOfRange(0, 48))
                remoteStatic = rs
                mixKey(dh(e!!, rs))
                decryptAndHash(msg.copyOfRange(48, 64))
                split(false)
                step = 3
                return null
            }
            else -> error("unexpected message")
        }
    }

    // ---- transport ------------------------------------------------------------------------------

    /** `nonce(4, big endian) ‖ ciphertext ‖ tag`, the layout the official apps exchange. */
    @Synchronized
    fun encrypt(plain: ByteArray): ByteArray {
        val key = send ?: error("not established")
        val c = aead(true, key, sent, ByteArray(0), plain)
        val out = ByteArray(4 + c.size)
        out[0] = (sent ushr 24).toByte(); out[1] = (sent ushr 16).toByte(); out[2] = (sent ushr 8).toByte(); out[3] = sent.toByte()
        System.arraycopy(c, 0, out, 4, c.size)
        sent++
        return out
    }

    @Synchronized
    fun decrypt(data: ByteArray): ByteArray {
        val key = recv ?: error("not established")
        require(data.size >= 4 + 16)
        val nonce = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
        require(nonce + 1024 > highest && nonce !in seen) { "replay" }
        val plain = aead(false, key, nonce, ByteArray(0), data.copyOfRange(4, data.size))
        seen += nonce
        if (nonce > highest) highest = nonce
        seen.removeAll { it + 1024 <= highest }
        return plain
    }

    val messageCount get() = sent

    // ---- Noise symmetric state ----------------------------------------------------------------------

    private fun pub(priv: ByteArray) = ByteArray(32).also { X25519.scalarMultBase(priv, 0, it, 0) }

    private fun dh(priv: ByteArray, peer: ByteArray): ByteArray {
        val out = ByteArray(32)
        require(X25519.calculateAgreement(priv, 0, peer, 0, out, 0)) { "bad key" }
        return out
    }

    private fun mixHash(d: ByteArray) {
        sha.reset(); sha.update(h); sha.update(d)
        h = sha.digest()
    }

    private fun mixKey(input: ByteArray) {
        val (a, b) = hkdf(ck, input)
        ck = a; k = b; n = 0
    }

    private fun encryptAndHash(plain: ByteArray): ByteArray {
        val key = k ?: return plain.also { mixHash(it) }
        val c = aead(true, key, n++, h, plain)
        mixHash(c)
        return c
    }

    private fun decryptAndHash(cipher: ByteArray): ByteArray {
        val key = k ?: return cipher.also { mixHash(it) }
        val p = aead(false, key, n++, h, cipher)
        mixHash(cipher)
        return p
    }

    private fun split(asInitiator: Boolean) {
        val (a, b) = hkdf(ck, ByteArray(0))
        send = if (asInitiator) a else b
        recv = if (asInitiator) b else a
        e = null; k = null
    }

    companion object {
        private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val m = Mac.getInstance("HmacSHA256")
            m.init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { m.update(it) }
            return m.doFinal()
        }

        fun hkdf(chaining: ByteArray, input: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(chaining, input)
            val o1 = hmac(temp, byteArrayOf(1))
            val o2 = hmac(temp, o1, byteArrayOf(2))
            return o1 to o2
        }

        /** ChaChaPoly nonce in Noise: 32 zero bits then the counter, little endian. */
        private fun aead(encrypt: Boolean, key: ByteArray, counter: Long, ad: ByteArray, data: ByteArray): ByteArray {
            val nonce = ByteArray(12)
            for (i in 0 until 8) nonce[4 + i] = (counter ushr (8 * i)).toByte()
            val c = ChaCha20Poly1305()
            c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, ad))
            val out = ByteArray(c.getOutputSize(data.size))
            var n = c.processBytes(data, 0, data.size, out, 0)
            n += c.doFinal(out, n)
            return out.copyOf(n)
        }
    }
}
