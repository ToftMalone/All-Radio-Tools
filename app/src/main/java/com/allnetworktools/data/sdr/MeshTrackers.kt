package com.allnetworktools.data.sdr

/** A node of a mesh network, as heard so far: identity, position and the last values it reported. */
data class MeshNode(
    val key: String,
    /** "!a1b2c3d4" for Meshtastic, the start of the public key for MeshCore. */
    val id: String,
    val longName: String? = null,
    val shortName: String? = null,
    val hardware: String? = null,
    val role: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Int? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    val channelUtil: Float? = null,
    val airUtilTx: Float? = null,
    val uptimeS: Int? = null,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val pressure: Float? = null,
    val snrDb: Double? = null,
    val hops: Int? = null,
    val packets: Int = 0,
    val lastMs: Long = 0,
    /** MeshCore: the advert's signature checked (null where Android cannot check it). */
    val verified: Boolean? = null,
    /** Meshtastic: only its encrypted packets were heard. */
    val encryptedOnly: Boolean = false,
    val viaMqtt: Boolean = false,
) {
    val title get() = longName ?: shortName ?: id
}

/** A text message on a channel anyone can read. */
data class MeshMessage(
    val key: String,
    val timeMs: Long,
    val fromKey: String?,
    val fromName: String,
    /** The recipient when it is not everyone (Meshtastic). */
    val toName: String?,
    val channel: String?,
    val text: String,
    val snrDb: Double?,
    val hops: Int?,
    /** How many copies were heard, relayed by different nodes. */
    val copies: Int,
)

/** One received packet, for the packet log. */
data class MeshPacketRow(
    val timeMs: Long,
    val from: String,
    val to: String?,
    val kind: String,
    val detail: String?,
    val encrypted: Boolean,
    val hz: Long,
    val sf: Int,
    val bwHz: Double,
    val snrDb: Double,
    val hops: Int?,
    val bytes: Int,
)

/** What the trackers publish, about once a second. */
class MeshSnapshot(val nodes: List<MeshNode>, val messages: List<MeshMessage>, val packets: List<MeshPacketRow>, val total: Int, val readable: Int, val badCrc: Int) {
    companion object { val Empty = MeshSnapshot(emptyList(), emptyList(), emptyList(), 0, 0, 0) }
}

/** What both trackers do: take frames on the DSP thread, give a snapshot for the UI, start over. */
interface MeshTracker {
    fun update(f: MeshFrame, nowMs: Long)
    fun snapshot(nowMs: Long): MeshSnapshot
    fun clear()
}

private const val NODE_TTL_MS = 3 * 3_600_000L
private const val MAX_MESSAGES = 300
private const val MAX_PACKETS = 200

/**
 * Meshtastic nodes, messages and packets from received frames. A packet relayed by several nodes is one packet (same
 * sender and id): the copies are counted, the closest copy (fewest hops) is kept.
 */
class MeshtasticTracker(@Volatile var channels: List<Meshtastic.Channel> = Meshtastic.publicChannels) : MeshTracker {
    private val nodes = LinkedHashMap<Int, MeshNode>()
    private val messages = ArrayList<MeshMessage>()
    private val packets = ArrayDeque<MeshPacketRow>()
    private val seen = LinkedHashMap<Long, Long>()
    var total = 0; private set
    var readable = 0; private set
    var badCrc = 0; private set

    private fun name(num: Int): String = if (num == Meshtastic.BROADCAST) "*" else nodes[num]?.title ?: Meshtastic.nodeId(num)

    override fun update(f: MeshFrame, nowMs: Long) {
        if (!f.frame.crcOk) { if (f.frame.syncWord == Meshtastic.SYNC_WORD) badCrc++; return }
        val p = Meshtastic.decode(f.frame.payload, channels) ?: return
        // Another network on the same channel (MeshCore, LoRaWAN) has another sync word and nothing a key opens.
        if (f.frame.syncWord != Meshtastic.SYNC_WORD && p.data == null) return
        val h = p.header
        if (h.from == 0 || h.from == Meshtastic.BROADCAST) return
        val dupKey = (h.from.toLong() shl 32) or (h.id.toLong() and 0xFFFFFFFFL)
        val again = seen.containsKey(dupKey)
        seen.remove(dupKey); seen[dupKey] = nowMs
        while (seen.size > 2000) seen.remove(seen.keys.first())
        if (!again) total++
        if (!again && p.data != null) readable++

        val prev = nodes[h.from] ?: MeshNode(Meshtastic.nodeId(h.from), Meshtastic.nodeId(h.from))
        val hopsNow = h.hops
        val hopsBefore = prev.hops
        var n = prev.copy(
            lastMs = nowMs, packets = prev.packets + if (again) 0 else 1, viaMqtt = h.viaMqtt,
            // A relayed copy says less about how well the node itself is heard than the closer one.
            snrDb = if (again && hopsBefore != null && hopsNow != null && hopsNow > hopsBefore) prev.snrDb else f.frame.snrDb,
            hops = listOfNotNull(if (again) prev.hops else null, h.hops).minOrNull(),
            encryptedOnly = prev.encryptedOnly.takeIf { prev.packets > 0 } ?: true,
        )
        val d = p.data
        var kind = if (p.pki) "PKI" else if (d == null) "?" else Meshtastic.portName(d.port)
        var detail: String? = null
        if (d != null) {
            n = n.copy(encryptedOnly = false)
            when (d.port) {
                Meshtastic.PORT_TEXT -> {
                    val text = String(d.payload, Charsets.UTF_8)
                    detail = text
                    val key = "%08x/%08x".format(h.from, h.id)
                    val i = messages.indexOfFirst { it.key == key }
                    if (i >= 0) {
                        val m = messages[i]
                        messages[i] = m.copy(copies = m.copies + 1, hops = listOfNotNull(m.hops, h.hops).minOrNull())
                    } else {
                        messages += MeshMessage(
                            key, nowMs, Meshtastic.nodeId(h.from), name(h.from), if (h.broadcast) null else name(h.to),
                            p.channel?.name, text, f.frame.snrDb, h.hops, 1,
                        )
                        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
                    }
                }
                Meshtastic.PORT_POSITION -> Meshtastic.position(d.payload)?.let { pos ->
                    if (pos.lat != null) n = n.copy(lat = pos.lat, lon = pos.lon, altitudeM = pos.altitudeM ?: n.altitudeM)
                    detail = pos.lat?.let { "%.5f, %.5f".format(java.util.Locale.ROOT, pos.lat, pos.lon) }
                }
                Meshtastic.PORT_NODEINFO -> Meshtastic.user(d.payload)?.let { u ->
                    n = n.copy(
                        longName = u.longName ?: n.longName, shortName = u.shortName ?: n.shortName,
                        hardware = u.hwModel?.let(Meshtastic::hardwareName) ?: n.hardware, role = u.role?.let(Meshtastic::roleName) ?: n.role,
                    )
                    detail = listOfNotNull(u.longName, u.shortName?.let { "($it)" }).joinToString(" ")
                }
                Meshtastic.PORT_TELEMETRY -> Meshtastic.telemetry(d.payload)?.let { t ->
                    n = n.copy(
                        battery = t.battery ?: n.battery, voltage = t.voltage ?: n.voltage, channelUtil = t.channelUtil ?: n.channelUtil,
                        airUtilTx = t.airUtilTx ?: n.airUtilTx, uptimeS = t.uptimeS ?: n.uptimeS,
                        temperature = t.temperature ?: n.temperature, humidity = t.humidity ?: n.humidity, pressure = t.pressure ?: n.pressure,
                    )
                    detail = listOfNotNull(
                        t.battery?.let { if (it > 100) "USB" else "$it %" }, t.voltage?.let { "%.2f V".format(java.util.Locale.ROOT, it) },
                        t.temperature?.let { "%.1f °C".format(java.util.Locale.ROOT, it) }, t.humidity?.let { "%.0f %%".format(java.util.Locale.ROOT, it) },
                        t.pressure?.let { "%.0f hPa".format(java.util.Locale.ROOT, it) },
                    ).joinToString(" · ").ifEmpty { null }
                }
                Meshtastic.PORT_MAP_REPORT -> Meshtastic.mapReport(d.payload)?.let { r ->
                    n = n.copy(
                        longName = r.longName ?: n.longName, shortName = r.shortName ?: n.shortName,
                        hardware = r.hwModel?.let(Meshtastic::hardwareName) ?: n.hardware, role = r.role?.let(Meshtastic::roleName) ?: n.role,
                        lat = r.lat ?: n.lat, lon = r.lon ?: n.lon,
                    )
                    detail = r.firmware
                }
                Meshtastic.PORT_TRACEROUTE -> Meshtastic.route(d.payload)?.let { r ->
                    detail = (listOf(name(h.from)) + r.towards.map(::name) + listOf(name(h.to))).joinToString(" → ")
                }
                Meshtastic.PORT_NEIGHBORINFO -> Meshtastic.neighbors(d.payload)?.let { l -> detail = l.joinToString(", ") { name(it.first) } }
                Meshtastic.PORT_WAYPOINT -> Meshtastic.waypoint(d.payload)?.let { w -> detail = listOfNotNull(w.name, w.description).joinToString(" · ") }
                Meshtastic.PORT_ROUTING -> Meshtastic.routingError(d.payload)?.let { e -> detail = if (e == 0) "ACK" else "error $e"; kind = "ROUTING" }
            }
        }
        nodes[h.from] = n
        if (!again) {
            packets.addFirst(
                MeshPacketRow(
                    nowMs, name(h.from), if (h.broadcast) null else name(h.to), kind, detail, d == null, f.hz, f.sf, f.bwHz,
                    f.frame.snrDb, h.hops, f.frame.payload.size,
                ),
            )
            while (packets.size > MAX_PACKETS) packets.removeLast()
        }
    }

    override fun clear() { nodes.clear(); messages.clear(); packets.clear(); seen.clear(); total = 0; readable = 0; badCrc = 0 }

    override fun snapshot(nowMs: Long): MeshSnapshot {
        nodes.values.removeAll { nowMs - it.lastMs > NODE_TTL_MS }
        return MeshSnapshot(nodes.values.sortedByDescending { it.lastMs }, messages.reversed(), packets.toList(), total, readable, badCrc)
    }
}

/**
 * MeshCore nodes (from their adverts), group messages and packets. A flood is repeated by every repeater with a longer
 * path: the same type and payload is one packet, heard once more.
 */
class MeshCoreTracker(@Volatile var channels: List<MeshCore.Channel> = listOf(MeshCore.PUBLIC)) : MeshTracker {
    private val nodes = LinkedHashMap<String, MeshNode>()
    private val messages = ArrayList<MeshMessage>()
    private val packets = ArrayDeque<MeshPacketRow>()
    private val seen = LinkedHashMap<String, Long>()
    var total = 0; private set
    var readable = 0; private set
    var badCrc = 0; private set

    /** A node by the id a path or a message gives (1 to 3 bytes of its key), when only one known node matches. */
    private fun byHash(h: ByteArray): MeshNode? {
        val prefix = MeshCore.hex(h)
        return nodes.values.filter { it.key.startsWith(prefix) }.singleOrNull()
    }

    private fun hashName(h: ByteArray) = byHash(h)?.title ?: MeshCore.hex(h).uppercase()

    override fun update(f: MeshFrame, nowMs: Long) {
        if (!f.frame.crcOk) { if (f.frame.syncWord == MeshCore.SYNC_WORD) badCrc++; return }
        if (f.frame.syncWord != MeshCore.SYNC_WORD) return
        val p = MeshCore.parse(f.frame.payload) ?: return
        val dupKey = p.type.toString() + "/" + MeshCore.hex(p.payload)
        val again = seen.containsKey(dupKey)
        seen.remove(dupKey); seen[dupKey] = nowMs
        while (seen.size > 2000) seen.remove(seen.keys.first())
        if (!again) total++
        val hops = if (p.flood) p.path.size else null
        var from = "?"
        var to: String? = null
        var detail: String? = null
        var encrypted = false
        when (p.type) {
            MeshCore.TYPE_ADVERT -> MeshCore.advert(p.payload)?.let { a ->
                val key = a.keyHex
                val prev = nodes[key] ?: MeshNode(key, key.take(8))
                val role = when (a.nodeType) { MeshCore.NODE_CHAT -> "CHAT"; MeshCore.NODE_REPEATER -> "REPEATER"; MeshCore.NODE_ROOM -> "ROOM"; MeshCore.NODE_SENSOR -> "SENSOR"; else -> null }
                nodes.remove(key)
                nodes[key] = prev.copy(
                    longName = a.name ?: prev.longName, role = role ?: prev.role, lat = a.lat ?: prev.lat, lon = a.lon ?: prev.lon,
                    snrDb = f.frame.snrDb, hops = listOfNotNull(if (again) prev.hops else null, hops).minOrNull(),
                    packets = prev.packets + if (again) 0 else 1, lastMs = nowMs, verified = prev.verified ?: MeshCore.verify(a),
                )
                if (!again) readable++
                from = a.name ?: key.take(8)
                detail = listOfNotNull(role, a.lat?.let { "%.5f, %.5f".format(java.util.Locale.ROOT, a.lat, a.lon) }).joinToString(" · ")
            }
            MeshCore.TYPE_GRP_TXT -> {
                val t = MeshCore.groupText(p.payload, channels)
                if (t == null) { encrypted = true; detail = "#%02X".format(p.payload[0].toInt() and 0xFF) } else {
                    if (!again) readable++
                    from = t.sender ?: "?"
                    detail = t.text
                    val key = dupKey
                    val i = messages.indexOfFirst { it.key == key }
                    if (i >= 0) {
                        val m = messages[i]
                        messages[i] = m.copy(copies = m.copies + 1, hops = listOfNotNull(m.hops, hops).minOrNull())
                    } else {
                        messages += MeshMessage(key, nowMs, null, t.sender ?: "?", null, t.channel.name, t.text, f.frame.snrDb, hops, 1)
                        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
                    }
                }
            }
            MeshCore.TYPE_GRP_DATA -> { encrypted = MeshCore.openGroup(p.payload, channels) == null; detail = "#%02X".format(p.payload[0].toInt() and 0xFF) }
            else -> MeshCore.addressees(p.type, p.payload)?.let { (d, s) ->
                encrypted = true
                to = hashName(byteArrayOf(d.toByte()))
                if (s != null) from = hashName(byteArrayOf(s.toByte()))
            }
        }
        // The last repeater of a flood is the one heard directly.
        p.path.lastOrNull()?.let { last -> byHash(last)?.let { r -> nodes[r.key] = r.copy(lastMs = nowMs, snrDb = f.frame.snrDb, hops = 0) } }
        if (!again) {
            val route = if (p.path.isEmpty()) null else p.path.joinToString(" → ") { hashName(it) }
            packets.addFirst(
                MeshPacketRow(
                    nowMs, from, to, MeshCore.typeName(p.type), listOfNotNull(detail?.takeIf { it.isNotEmpty() }, route?.let { "via $it" }).joinToString(" · ").ifEmpty { null },
                    encrypted, f.hz, f.sf, f.bwHz, f.frame.snrDb, hops, f.frame.payload.size,
                ),
            )
            while (packets.size > MAX_PACKETS) packets.removeLast()
        }
    }

    override fun clear() { nodes.clear(); messages.clear(); packets.clear(); seen.clear(); total = 0; readable = 0; badCrc = 0 }

    override fun snapshot(nowMs: Long): MeshSnapshot {
        nodes.values.removeAll { nowMs - it.lastMs > NODE_TTL_MS }
        return MeshSnapshot(nodes.values.sortedByDescending { it.lastMs }, messages.reversed(), packets.toList(), total, readable, badCrc)
    }
}
