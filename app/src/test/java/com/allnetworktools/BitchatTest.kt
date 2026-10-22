package com.allnetworktools

import com.allnetworktools.data.bitchat.Announcement
import com.allnetworktools.data.bitchat.BinaryProtocol
import com.allnetworktools.data.bitchat.Bitchat
import com.allnetworktools.data.bitchat.BitchatMesh
import com.allnetworktools.data.bitchat.Fragments
import com.allnetworktools.data.bitchat.Identity
import com.allnetworktools.data.bitchat.MeshLink
import com.allnetworktools.data.bitchat.NoisePayload
import com.allnetworktools.data.bitchat.NoiseSession
import com.allnetworktools.data.bitchat.Packet
import com.allnetworktools.data.bitchat.Reassembler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitchatTest {
    private class Net {
        class Node(val name: String) : MeshLink {
            lateinit var mesh: BitchatMesh
            val edges = ArrayList<Pair<String, Node>>()
            lateinit var net: Net
            override fun send(conn: String, data: ByteArray) {
                edges.firstOrNull { it.first == conn }?.let { (_, other) -> net.queue += { other.mesh.receive(other.name + ">" + name, data) } }
            }
            override fun sendAll(data: ByteArray, except: String?) {
                edges.filter { it.first != except }.forEach { (c, _) -> send(c, data) }
            }
        }

        val queue = ArrayDeque<() -> Unit>()
        fun node(name: String, nick: String): Node {
            val n = Node(name)
            n.net = this
            n.mesh = BitchatMesh(Identity.generate(), nick, n)
            n.mesh.start()
            return n
        }

        /** Links a and b; each side names the connection after the other so a relay can skip where it came from. */
        fun connect(a: Node, b: Node) {
            a.edges += (b.name + ">" + a.name) to b
            b.edges += (a.name + ">" + b.name) to a
            a.mesh.linkUp(b.name + ">" + a.name)
            b.mesh.linkUp(a.name + ">" + b.name)
            pump()
        }

        fun pump() {
            var n = 0
            while (queue.isNotEmpty() && n++ < 100_000) queue.removeFirst()()
        }
    }

    @Test
    fun packetRoundTripKeepsSignatureAndCompression() {
        val id = Identity.generate()
        val text = "bonjour ".repeat(60).toByteArray()
        val p = Packet(type = Bitchat.TYPE_MESSAGE, ttl = 7, timestamp = 1_700_000_000_000, sender = Bitchat.unhex(id.peerId), recipient = Bitchat.BROADCAST, payload = text)
        p.signature = id.sign(p.signingBytes()!!)
        val wire = p.encode()!!
        assertTrue(wire.size < text.size)
        assertEquals(0, wire.size.let { sz -> listOf(256, 512, 1024, 2048).count { it == sz } }.minus(1))
        val back = BinaryProtocol.decode(wire)!!
        assertArrayEquals(text, back.payload)
        assertTrue(Identity.verify(id.signingPublic, back.signingBytes()!!, back.signature!!))
        // A relay lowers the TTL without breaking the signature.
        val relayed = BinaryProtocol.decode(back.copy(ttl = 3).encode()!!)!!
        assertEquals(3, relayed.ttl)
        assertTrue(Identity.verify(id.signingPublic, relayed.signingBytes()!!, relayed.signature!!))
    }

    @Test
    fun peerIdIsHashOfNoiseKey() {
        val id = Identity.generate()
        assertEquals(16, id.peerId.length)
        assertEquals(id.peerId, Bitchat.peerId(id.noisePublic))
    }

    @Test
    fun announcementRoundTrip() {
        val id = Identity.generate()
        val enc = Announcement("alice", id.noisePublic, id.signingPublic, listOf("0011223344556677")).encode()!!
        val a = Announcement.decode(enc)!!
        assertEquals("alice", a.nickname)
        assertArrayEquals(id.noisePublic, a.noiseKey)
        assertEquals(listOf("0011223344556677"), a.neighbors)
    }

    @Test
    fun noiseXxHandshakeAndTransport() {
        val a = Identity.generate()
        val b = Identity.generate()
        val i = NoiseSession(a, initiator = true)
        val r = NoiseSession(b, initiator = false)
        val m1 = i.start()
        assertEquals(32, m1.size)
        val m2 = r.read(m1)!!
        assertEquals(96, m2.size)
        val m3 = i.read(m2)!!
        assertEquals(64, m3.size)
        assertNull(r.read(m3))
        assertTrue(i.established && r.established)
        assertArrayEquals(b.noisePublic, i.remoteStatic)
        assertArrayEquals(a.noisePublic, r.remoteStatic)
        val c = i.encrypt("salut".toByteArray())
        assertEquals("salut", String(r.decrypt(c)))
        assertEquals("ok", String(i.decrypt(r.encrypt("ok".toByteArray()))))
        runCatching { r.decrypt(c) }.let { assertTrue("replay refused", it.isFailure) }
    }

    /** Messages produced by the reference Python `noiseprotocol` library with the same fixed keys. */
    @Test
    fun noiseXxMatchesReferenceImplementation() {
        fun fixed(b: Int) = object : java.security.SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(b.toByte()) }
        }
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val a = Identity(ByteArray(32) { 1 }, ByteArray(32) { 9 })
        val b = Identity(ByteArray(32) { 2 }, ByteArray(32) { 8 })
        val i = NoiseSession(a, true, fixed(3))
        val r = NoiseSession(b, false, fixed(4))
        val m1 = i.start()
        assertEquals("5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef22", Bitchat.hex(m1))
        val m2 = r.read(m1)!!
        assertEquals("ac01b2209e86354fb853237b5de0f4fab13c7fcbf433a61c019369617fecf10b85e987f334561c4942a61ab2cce4d2176528515ce90a44cc982d339ea8098552e889da6d59059d9a6820065c3f28b3497080949a373e7d4d0e5896b064d53160", Bitchat.hex(m2))
        val m3 = i.read(m2)!!
        assertEquals("c3e615be84281bf0e2732370ffa2becd8110548e0d540bb01de64834d81268f365a256ee58640493765d0a5f1fd343e18a901716650f0bb3828f59a0ea459736", Bitchat.hex(m3))
        r.read(m3)
        // The reference prints the AEAD output; ours prefixes it with the 4-byte nonce the official apps add.
        assertEquals("00000000" + "47e1a61a3a0fda14a32b4cff027befdfd887521b66", Bitchat.hex(i.encrypt("hello".toByteArray())))
        assertEquals("00000000" + "d28f4711547a14f94a4ad80f9303e42c435a8805fa", Bitchat.hex(r.encrypt("world".toByteArray())))
    }

    @Test
    fun largePacketsAreFragmentedAndReassembled() {
        val id = Identity.generate()
        val rnd = java.util.Random(1)
        val body = ByteArray(2500).also { rnd.nextBytes(it) }
        val p = Packet(type = Bitchat.TYPE_MESSAGE, ttl = 7, timestamp = 5, sender = Bitchat.unhex(id.peerId), recipient = Bitchat.BROADCAST, payload = body)
        p.signature = id.sign(p.signingBytes()!!)
        val parts = Fragments.split(p)
        assertTrue(parts.size > 1)
        val re = Reassembler()
        var whole: Packet? = null
        parts.shuffled(java.util.Random(2)).forEach { f -> BinaryProtocol.decode(f.encode()!!)!!.let { re.add(it)?.let { w -> whole = w } } }
        assertNotNull(whole)
        assertArrayEquals(body, whole!!.payload)
        assertTrue(Identity.verify(id.signingPublic, whole!!.signingBytes()!!, whole!!.signature!!))
    }

    @Test
    fun publicMessageCrossesTheMesh() {
        val net = Net()
        val a = net.node("a", "alice")
        val b = net.node("b", "bob")
        val c = net.node("c", "carol")
        net.connect(a, b)
        net.connect(b, c)
        net.pump()
        a.mesh.announce(); net.pump()
        c.mesh.announce(); net.pump()
        assertTrue(a.mesh.state.value.peers.any { it.nickname == "carol" })
        assertTrue(a.mesh.state.value.peers.first { it.nickname == "bob" }.direct)
        assertFalse(a.mesh.state.value.peers.first { it.nickname == "carol" }.direct)
        a.mesh.sendPublic("hello mesh"); net.pump()
        assertEquals("hello mesh", c.mesh.state.value.publicChat.single().text)
        assertEquals("alice", c.mesh.state.value.publicChat.single().nickname)
        assertTrue(b.mesh.state.value.relayed > 0)
        assertEquals(1, b.mesh.state.value.publicChat.size)
    }

    @Test
    fun privateChatBetweenTwoDistantPeers() {
        val net = Net()
        val a = net.node("a", "alice")
        val b = net.node("b", "bob")
        val c = net.node("c", "carol")
        net.connect(a, b); net.connect(b, c)
        a.mesh.announce(); c.mesh.announce(); net.pump()
        val carol = a.mesh.state.value.peers.first { it.nickname == "carol" }.id
        val alice = c.mesh.state.value.peers.first { it.nickname == "alice" }.id
        a.mesh.sendPrivate(carol, "secret"); net.pump()
        assertEquals("secret", c.mesh.state.value.privateChats[alice]!!.single().text)
        assertTrue(c.mesh.state.value.unread.contains(alice))
        assertEquals("delivered", "delivered".takeIf { a.mesh.state.value.privateChats[carol]!!.single().delivery.name == "Delivered" })
        assertTrue(a.mesh.state.value.peers.first { it.id == carol }.encrypted)
        // the relay in the middle saw only ciphertext: it holds no copy of the private chat
        assertTrue(b.mesh.state.value.privateChats.isEmpty())
        c.mesh.openChat(alice); net.pump()
        assertEquals("Read", a.mesh.state.value.privateChats[carol]!!.single().delivery.name)
        c.mesh.sendPrivate(alice, "reçu"); net.pump()
        assertEquals("reçu", a.mesh.state.value.privateChats[carol]!!.last().text)
        assertEquals(NoisePayload.PRIVATE_MESSAGE, 1)
    }

    @Test
    fun simultaneousHandshakesStillConverge() {
        val net = Net()
        val a = net.node("a", "alice")
        val b = net.node("b", "bob")
        net.connect(a, b)
        a.mesh.announce(); b.mesh.announce(); net.pump()
        val bob = a.mesh.state.value.peers.single().id
        val alice = b.mesh.state.value.peers.single().id
        a.mesh.sendPrivate(bob, "one")
        b.mesh.sendPrivate(alice, "two")
        net.pump()
        assertEquals("two", a.mesh.state.value.privateChats[bob]!!.first { !it.mine }.text)
        assertEquals("one", b.mesh.state.value.privateChats[alice]!!.first { !it.mine }.text)
    }

    @Test
    fun favouriteNoticeIsHiddenAndMutualIsDetected() {
        val net = Net()
        val a = net.node("a", "alice")
        val b = net.node("b", "bob")
        net.connect(a, b)
        a.mesh.announce(); b.mesh.announce(); net.pump()
        val bob = a.mesh.state.value.peers.single().id
        val alice = b.mesh.state.value.peers.single().id
        a.mesh.setFavorite(bob, true, setOf(bob)); net.pump()
        val seen = b.mesh.state.value.privateChats[alice]!!.single()
        assertEquals(com.allnetworktools.data.bitchat.NoteKind.FavoritedYou, seen.note)
        assertTrue(seen.system)
        assertTrue(b.mesh.state.value.favoritedUs.contains(alice))
        assertTrue(a.mesh.state.value.privateChats[bob].isNullOrEmpty())
        b.mesh.setFavorite(alice, true, setOf(alice)); net.pump()
        assertEquals(com.allnetworktools.data.bitchat.NoteKind.FavoritedYouMutual, a.mesh.state.value.privateChats[bob]!!.single().note)
    }

    @Test
    fun blockedPeerIsIgnoredAndClearWorks() {
        val net = Net()
        val a = net.node("a", "alice")
        val b = net.node("b", "bob")
        net.connect(a, b)
        a.mesh.announce(); b.mesh.announce(); net.pump()
        val bob = a.mesh.state.value.peers.single().id
        a.mesh.setBlocked(setOf(bob))
        b.mesh.sendPublic("hi"); net.pump()
        assertTrue(a.mesh.state.value.publicChat.isEmpty())
        a.mesh.setBlocked(emptySet())
        b.mesh.sendPublic("again"); net.pump()
        assertEquals(1, a.mesh.state.value.publicChat.size)
        a.mesh.clear(null)
        assertTrue(a.mesh.state.value.publicChat.isEmpty())
    }

    @Test
    fun forgedAnnouncementIsRejected() {
        val net = Net()
        val a = net.node("a", "alice")
        val mallory = Identity.generate()
        val other = Identity.generate()
        // Claims other's peer ID with mallory's keys.
        val ann = Announcement("evil", mallory.noisePublic, mallory.signingPublic, null).encode()!!
        val p = Packet(type = Bitchat.TYPE_ANNOUNCE, ttl = 7, timestamp = System.currentTimeMillis(), sender = Bitchat.unhex(other.peerId), payload = ann)
        p.signature = mallory.sign(p.signingBytes()!!)
        a.mesh.linkUp("x")
        a.mesh.receive("x", p.encode()!!)
        assertTrue(a.mesh.state.value.peers.isEmpty())
    }
}
