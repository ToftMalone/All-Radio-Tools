package com.allnetworktools.data.bitchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

/** Whatever carries bytes to the devices around: the Bluetooth LE GATT links in the app, a loopback in tests. */
interface MeshLink {
    fun send(conn: String, data: ByteArray)
    fun sendAll(data: ByteArray, except: String? = null)
}

enum class Delivery { Sending, Sent, Delivered, Read }

/** Notices the engine itself raises in a chat; the screen words them in the user's language. */
enum class NoteKind { FavoritedYou, FavoritedYouMutual, UnfavoritedYou }

data class ChatMessage(
    val id: String,
    val from: String,
    val nickname: String,
    val text: String,
    val time: Long,
    val mine: Boolean,
    val delivery: Delivery = Delivery.Sent,
    val system: Boolean = false,
    val note: NoteKind? = null,
)

data class MeshPeer(val id: String, val nickname: String, val direct: Boolean, val lastSeen: Long, val encrypted: Boolean)

data class MeshState(
    val running: Boolean = false,
    val peerId: String = "",
    val nickname: String = "",
    val links: Int = 0,
    val peers: List<MeshPeer> = emptyList(),
    val publicChat: List<ChatMessage> = emptyList(),
    val privateChats: Map<String, List<ChatMessage>> = emptyMap(),
    val unread: Set<String> = emptySet(),
    val favoritedUs: Set<String> = emptySet(),
    val blocked: Set<String> = emptySet(),
    val relayed: Long = 0,
    val received: Long = 0,
    val sent: Long = 0,
)

/**
 * The BitChat mesh engine: identity announcements, signed public messages, Noise-encrypted private chats,
 * fragmentation and TTL relaying, with the same rules as the official apps so both sides see each other.
 * It is transport-agnostic: [MeshLink] moves the bytes, and every entry point is synchronized.
 */
class BitchatMesh(
    private val identity: Identity,
    nickname: String,
    private val link: MeshLink,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Known(
        var nickname: String,
        val noiseKey: ByteArray,
        val signingKey: ByteArray,
        var lastSeen: Long,
        var neighbors: List<String>? = null,
    )

    private class Pending(val id: String, val text: String, val hidden: Boolean = false)

    private val me = identity.peerId
    private val rng = SecureRandom()
    private val peers = LinkedHashMap<String, Known>()
    private val connPeer = HashMap<String, String>()
    private val conns = HashSet<String>()
    private val sessions = HashMap<String, NoiseSession>()
    private val failures = HashMap<String, Int>()
    private val outbox = HashMap<String, MutableList<Pending>>()
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>) = size > 10_000
    }
    private val reassembler = Reassembler()
    private var lastStamp = 0L
    private var lastAnnounce = 0L
    private var nick = nickname
    private var running = false
    private var relayed = 0L
    private var received = 0L
    private var sentCount = 0L
    private val publicChat = ArrayList<ChatMessage>()
    private val privateChats = HashMap<String, MutableList<ChatMessage>>()
    private val unread = HashSet<String>()
    private var openChat: String? = null
    private var myFavorites = emptySet<String>()
    private var favoritedUs = emptySet<String>()
    private var blocked = emptySet<String>()

    /** Called when a peer favourites or unfavourites us, so the set can be saved. */
    var onFavoritedUsChanged: (Set<String>) -> Unit = {}

    private val _state = MutableStateFlow(MeshState(peerId = me, nickname = nickname))
    val state: StateFlow<MeshState> = _state.asStateFlow()

    // ---- lifecycle ----------------------------------------------------------------------------------

    @Synchronized
    fun start() {
        running = true
        publish()
    }

    @Synchronized
    fun stop() {
        if (running) broadcast(Packet(type = Bitchat.TYPE_LEAVE, ttl = Bitchat.TTL, timestamp = stamp(), sender = Bitchat.unhex(me), payload = ByteArray(0)))
        running = false
        conns.clear(); connPeer.clear(); peers.clear(); sessions.clear(); outbox.clear()
        publish()
    }

    @Synchronized
    fun setNickname(value: String) {
        nick = value.trim().take(40).ifEmpty { nick }
        announce()
        publish()
    }

    @Synchronized
    fun linkUp(conn: String) {
        conns += conn
        sendTo(conn, announcePacket())
        publish()
    }

    @Synchronized
    fun linkDown(conn: String) {
        conns -= conn
        connPeer.remove(conn)
        publish()
    }

    /** Called about every 10 s: refreshes our announcement and forgets peers not heard for three minutes. */
    @Synchronized
    fun tick() {
        if (!running) return
        val t = now()
        if (peers.entries.removeAll { t - it.value.lastSeen > 180_000 }) {
            connPeer.values.removeAll { it !in peers }
        }
        sessions.entries.removeAll { !it.value.established && t - it.value.startedAt > 10_000 }
        if (conns.isNotEmpty() && t - lastAnnounce > 20_000) announce()
        publish()
    }

    // ---- sending ------------------------------------------------------------------------------------

    @Synchronized
    fun announce() {
        if (!running || conns.isEmpty()) return
        lastAnnounce = now()
        broadcast(announcePacket())
    }

    @Synchronized
    fun sendPublic(text: String) {
        val t = text.trim()
        if (!running || t.isEmpty()) return
        val p = Packet(
            type = Bitchat.TYPE_MESSAGE, ttl = Bitchat.TTL, timestamp = stamp(), sender = Bitchat.unhex(me),
            recipient = Bitchat.BROADCAST, payload = t.toByteArray(Charsets.UTF_8),
        )
        publicChat += ChatMessage(p.id().uppercase(), me, nick, t, p.timestamp, true)
        if (publicChat.size > 500) publicChat.removeAt(0)
        broadcast(p)
        publish()
    }

    @Synchronized
    fun sendPrivate(peer: String, text: String, hidden: Boolean = false) {
        if (!running || peer == me) return
        val t = text.trim()
        if (t.isEmpty()) return
        val list = privateChats.getOrPut(peer) { ArrayList() }
        chunks(t).forEach { part ->
            val id = UUID.randomUUID().toString()
            val ready = sessions[peer]?.established == true
            if (!hidden) list += ChatMessage(id, me, nick, part, now(), true, if (ready) Delivery.Sent else Delivery.Sending)
            if (ready) sendNoise(peer, NoisePayload.privateMessage(id, part)) else outbox.getOrPut(peer) { ArrayList() } += Pending(id, part, hidden)
        }
        if (sessions[peer]?.established != true) handshake(peer)
        publish()
    }

    /** The chat the user is looking at: incoming messages there count as read, and we tell the sender. */
    @Synchronized
    fun openChat(peer: String?) {
        openChat = peer
        if (peer != null) {
            unread -= peer
            privateChats[peer]?.filter { !it.mine && it.delivery != Delivery.Read }?.forEach { m ->
                if (sessions[peer]?.established == true) sendNoise(peer, NoisePayload.ack(NoisePayload.READ_RECEIPT, m.id))
                mark(peer, m.id, Delivery.Read, incoming = true)
            }
        }
        publish()
    }

    @Synchronized
    fun restore(favorites: Set<String>, favoritedUs: Set<String>, blocked: Set<String>) {
        myFavorites = favorites; this.favoritedUs = favoritedUs; this.blocked = blocked
        publish()
    }

    /** Favouriting tells the other phone with the same private control message the official app sends. */
    @Synchronized
    fun setFavorite(peer: String, on: Boolean, favorites: Set<String>) {
        myFavorites = favorites
        if (running && peer != me) sendPrivate(peer, if (on) "[FAVORITED]:" else "[UNFAVORITED]:", hidden = true)
        publish()
    }

    @Synchronized
    fun setBlocked(ids: Set<String>) {
        blocked = ids
        publish()
    }

    /** A local line in a chat (command results); `chat` is a peer ID, or null for the public channel. */
    @Synchronized
    fun note(chat: String?, text: String) {
        val m = ChatMessage(UUID.randomUUID().toString(), "", "", text, now(), false, system = true)
        if (chat == null) publicChat += m else privateChats.getOrPut(chat) { ArrayList() } += m
        publish()
    }

    @Synchronized
    fun clear(chat: String?) {
        if (chat == null) publicChat.clear() else privateChats[chat]?.clear()
        publish()
    }

    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        var cur = StringBuilder()
        var bytes = 0
        for (cp in text.codePoints().toArray()) {
            val s = String(Character.toChars(cp))
            val n = s.toByteArray(Charsets.UTF_8).size
            if (bytes + n > 240) { out += cur.toString(); cur = StringBuilder(); bytes = 0 }
            cur.append(s); bytes += n
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    // ---- receiving ----------------------------------------------------------------------------------

    @Synchronized
    fun receive(conn: String, data: ByteArray) {
        if (!running) return
        val p = BinaryProtocol.decode(data) ?: return
        received++
        handle(conn, p)
        publish()
    }

    private fun handle(conn: String, p: Packet, reassembled: Boolean = false) {
        if (p.senderId == me) return
        val id = p.id()
        val t = now()
        if (seen.containsKey(id)) return
        seen[id] = t

        val forMe = p.recipientId == me
        var valid = true
        when (p.type) {
            Bitchat.TYPE_ANNOUNCE -> valid = onAnnounce(conn, p)
            Bitchat.TYPE_MESSAGE -> valid = verified(p) && run { onMessage(p); true }
            Bitchat.TYPE_LEAVE -> valid = verified(p) && run { onLeave(p); true }
            Bitchat.TYPE_FRAGMENT -> reassembler.add(p, t)?.let { whole -> seen.remove(whole.id()); handle(conn, whole, reassembled = true) }
            Bitchat.TYPE_NOISE_HANDSHAKE -> if (forMe) onHandshake(p)
            Bitchat.TYPE_NOISE_ENCRYPTED -> if (forMe) onEncrypted(p)
        }
        // Fragments were already relayed one by one; a reassembled packet must not travel twice.
        if (!valid || forMe || reassembled) return
        relay(conn, p)
    }

    private fun verified(p: Packet): Boolean {
        val key = peers[p.senderId]?.signingKey ?: return false
        val sig = p.signature ?: return false
        val bytes = p.signingBytes() ?: return false
        return Identity.verify(key, bytes, sig)
    }

    private fun onAnnounce(conn: String, p: Packet): Boolean {
        val sig = p.signature ?: return false
        val a = Announcement.decode(p.payload) ?: return false
        if (a.noiseKey.size != 32 || a.signingKey.size != 32) return false
        if (Bitchat.peerId(a.noiseKey) != p.senderId) return false
        if (kotlin.math.abs(now() - p.timestamp) > 600_000) return false
        val bytes = p.signingBytes() ?: return false
        if (!Identity.verify(a.signingKey, bytes, sig)) return false
        val known = peers[p.senderId]
        if (known != null && !known.signingKey.contentEquals(a.signingKey)) return false
        val fresh = known == null
        val entry = known?.also { it.nickname = a.nickname; it.lastSeen = now(); it.neighbors = a.neighbors }
            ?: Known(a.nickname, a.noiseKey, a.signingKey, now(), a.neighbors).also { peers[p.senderId] = it }
        if (p.ttl >= Bitchat.TTL) connPeer[conn] = p.senderId
        if (fresh && entry.lastSeen - lastAnnounce > 1_000) {
            lastAnnounce = now()
            broadcast(announcePacket())
        }
        return true
    }

    private fun onMessage(p: Packet) {
        if (!p.isBroadcast) return
        val text = String(p.payload, Charsets.UTF_8)
        if (p.senderId in blocked) return
        val from = peers[p.senderId] ?: return
        from.lastSeen = now()
        publicChat += ChatMessage(p.id().uppercase(), p.senderId, from.nickname, text, p.timestamp, false)
        if (publicChat.size > 500) publicChat.removeAt(0)
    }

    private fun onLeave(p: Packet) {
        peers.remove(p.senderId)
        sessions.remove(p.senderId)
        connPeer.values.removeAll { it == p.senderId }
    }

    private fun relay(from: String, p: Packet) {
        if (p.ttl <= 0) return
        if (p.type == Bitchat.TYPE_REQUEST_SYNC) return
        val n = peers.size
        val chance = when {
            p.ttl >= 4 || n <= 3 -> 1.0
            n <= 10 -> 1.0
            n <= 30 -> 0.85
            n <= 50 -> 0.7
            n <= 100 -> 0.55
            else -> 0.4
        }
        if (chance < 1.0 && rng.nextDouble() > chance) return
        val out = p.copy(ttl = p.ttl - 1).encode() ?: return
        link.sendAll(out, except = from)
        relayed++
    }

    // ---- private chats ----------------------------------------------------------------------------------

    private fun handshake(peer: String) {
        if (peer !in peers) return
        val old = sessions[peer]
        if (old != null && !old.established && now() - old.startedAt < 10_000) return
        val s = NoiseSession(identity, initiator = true)
        sessions[peer] = s
        sendNoiseHandshake(peer, s.start())
    }

    private fun onHandshake(p: Packet) {
        val peer = p.senderId
        val s = sessions[peer]
        try {
            when {
                p.payload.size == 32 -> {
                    // They start a handshake. If we also started one, the larger peer ID yields.
                    if (s != null && !s.established && s.isInitiator && me > peer) {
                        // we yield: fall through and answer as responder
                    } else if (s != null && !s.established && s.isInitiator) {
                        return
                    }
                    val r = NoiseSession(identity, initiator = false)
                    sessions[peer] = r
                    r.read(p.payload)?.let { sendNoiseHandshake(peer, it) }
                }
                s != null && !s.established -> {
                    val reply = s.read(p.payload)
                    reply?.let { sendNoiseHandshake(peer, it) }
                    if (s.established && !bound(peer, s)) { sessions.remove(peer); return }
                    if (s.established) flush(peer)
                }
            }
        } catch (_: Exception) {
            sessions.remove(peer)
        }
    }

    private fun bound(peer: String, s: NoiseSession): Boolean = s.remoteStatic?.let { Bitchat.peerId(it) == peer } == true

    private fun onEncrypted(p: Packet) {
        val peer = p.senderId
        val s = sessions[peer]
        if (s == null || !s.established) { handshake(peer); return }
        val plain = try {
            s.decrypt(p.payload).also { failures[peer] = 0 }
        } catch (_: Exception) {
            val n = (failures[peer] ?: 0) + 1
            failures[peer] = n
            if (n >= 3) { sessions.remove(peer); failures[peer] = 0; handshake(peer) }
            return
        }
        if (!bound(peer, s) || plain.isEmpty()) return
        val body = plain.copyOfRange(1, plain.size)
        when (plain[0].toInt() and 0xFF) {
            NoisePayload.PRIVATE_MESSAGE -> {
                if (peer in blocked) return
                val (id, text) = NoisePayload.parsePrivate(body) ?: return
                val control = text.trim().let { if (it.startsWith("[FAVORITED]")) true else if (it.startsWith("[UNFAVORITED]")) false else null }
                if (control != null) {
                    onFavoriteNotice(peer, control)
                    sendNoise(peer, NoisePayload.ack(NoisePayload.DELIVERED, id))
                    return
                }
                val list = privateChats.getOrPut(peer) { ArrayList() }
                if (list.any { it.id == id }) return
                val nickname = peers[peer]?.nickname ?: peer
                val read = openChat == peer
                list += ChatMessage(id, peer, nickname, text, now(), false, if (read) Delivery.Read else Delivery.Sent)
                if (!read) unread += peer
                sendNoise(peer, NoisePayload.ack(NoisePayload.DELIVERED, id))
                if (read) sendNoise(peer, NoisePayload.ack(NoisePayload.READ_RECEIPT, id))
            }
            NoisePayload.DELIVERED -> mark(peer, String(body, Charsets.UTF_8), Delivery.Delivered)
            NoisePayload.READ_RECEIPT -> mark(peer, String(body, Charsets.UTF_8), Delivery.Read)
        }
    }

    private fun onFavoriteNotice(peer: String, favorited: Boolean) {
        favoritedUs = if (favorited) favoritedUs + peer else favoritedUs - peer
        onFavoritedUsChanged(favoritedUs)
        val nickname = peers[peer]?.nickname ?: peer
        val kind = if (!favorited) NoteKind.UnfavoritedYou else if (peer in myFavorites) NoteKind.FavoritedYouMutual else NoteKind.FavoritedYou
        privateChats.getOrPut(peer) { ArrayList() } += ChatMessage(UUID.randomUUID().toString(), peer, nickname, nickname, now(), false, system = true, note = kind)
    }

    private fun mark(peer: String, id: String, to: Delivery, incoming: Boolean = false) {
        val list = privateChats[peer] ?: return
        val i = list.indexOfFirst { it.id == id && it.mine != incoming }
        if (i >= 0 && list[i].delivery.ordinal < to.ordinal) list[i] = list[i].copy(delivery = to)
    }

    private fun flush(peer: String) {
        val queued = outbox.remove(peer) ?: return
        queued.forEach { q ->
            sendNoise(peer, NoisePayload.privateMessage(q.id, q.text))
            if (!q.hidden) mark(peer, q.id, Delivery.Sent)
        }
    }

    private fun sendNoise(peer: String, plain: ByteArray?) {
        val s = sessions[peer]?.takeIf { it.established } ?: return
        plain ?: return
        val p = Packet(
            type = Bitchat.TYPE_NOISE_ENCRYPTED, ttl = Bitchat.TTL, timestamp = stamp(), sender = Bitchat.unhex(me),
            recipient = Bitchat.unhex(peer), payload = s.encrypt(plain),
        )
        broadcast(p)
    }

    private fun sendNoiseHandshake(peer: String, msg: ByteArray) {
        broadcast(Packet(type = Bitchat.TYPE_NOISE_HANDSHAKE, ttl = Bitchat.TTL, timestamp = stamp(), sender = Bitchat.unhex(me), recipient = Bitchat.unhex(peer), payload = msg))
    }

    // ---- outbound plumbing ----------------------------------------------------------------------------

    private fun stamp(): Long {
        lastStamp = maxOf(now(), lastStamp + 1)
        return lastStamp
    }

    private fun announcePacket(): Packet {
        val direct = peers.keys.filter { id -> connPeer.containsValue(id) }
        val a = Announcement(nick, identity.noisePublic, identity.signingPublic, direct)
        return Packet(type = Bitchat.TYPE_ANNOUNCE, ttl = Bitchat.TTL, timestamp = stamp(), sender = Bitchat.unhex(me), payload = a.encode() ?: ByteArray(0))
    }

    private fun signed(p: Packet): Packet {
        val bytes = p.signingBytes() ?: return p
        return p.copy(signature = identity.sign(bytes))
    }

    private fun frames(p: Packet): List<ByteArray> {
        val s = signed(p)
        seen[s.id()] = now()
        return Fragments.split(s).mapNotNull { it.encode() }
    }

    private fun broadcast(p: Packet) {
        frames(p).forEach { link.sendAll(it) }
        sentCount++
    }

    private fun sendTo(conn: String, p: Packet) {
        frames(p).forEach { link.send(conn, it) }
        sentCount++
    }

    private fun publish() {
        val direct = connPeer.values.toSet()
        _state.value = MeshState(
            running = running,
            peerId = me,
            nickname = nick,
            links = conns.size,
            peers = peers.map { (id, k) -> MeshPeer(id, k.nickname, id in direct, k.lastSeen, sessions[id]?.established == true) }
                .sortedWith(compareByDescending<MeshPeer> { it.direct }.thenBy { it.nickname.lowercase() }),
            publicChat = publicChat.toList(),
            privateChats = privateChats.mapValues { it.value.toList() },
            unread = unread.toSet(),
            favoritedUs = favoritedUs,
            blocked = blocked,
            relayed = relayed,
            received = received,
            sent = sentCount,
        )
    }
}
