package com.allnetworktools.data.sdr

import java.io.ByteArrayOutputStream

/**
 * Meshtastic packets as a node sends them: the 16-byte header, then the `Data` protobuf encrypted with the channel key
 * (AES-CTR, [Meshtastic.crypt]). Only what a simple node announces: who it is (NodeInfo) and where it is (Position).
 */
object MeshtasticTx {
    /** "Private hardware" in the firmware's list: a node that is not one of the known boards. */
    const val HW_PRIVATE = 255

    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun int(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun fixed32(field: Int, v: Int) { tag(field, 5); for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xFF) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun done(): ByteArray = out.toByteArray()
    }

    /** `User`: id "!xxxxxxxx", long and short names, hardware model. */
    fun user(num: Int, longName: String, shortName: String, hw: Int = HW_PRIVATE): ByteArray = Pb().apply {
        string(1, Meshtastic.nodeId(num))
        string(2, longName.take(39))
        string(3, shortName.take(4))
        int(5, hw.toLong())
    }.done()

    /**
     * `Position`, rounded to [precisionBits] as the firmware does for a channel's position precision (32 is exact, 13 is
     * about 3 km): the low bits are cleared and the middle of the cell is sent.
     */
    fun position(lat: Double, lon: Double, altitudeM: Int?, timeS: Long, precisionBits: Int = 32): ByteArray {
        fun round(v: Int): Int = if (precisionBits >= 32) v else (v and (-1 shl (32 - precisionBits))) + (1 shl (31 - precisionBits))
        return Pb().apply {
            fixed32(1, round(Math.round(lat * 1e7).toInt()))
            fixed32(2, round(Math.round(lon * 1e7).toInt()))
            if (altitudeM != null) int(3, altitudeM.toLong())
            fixed32(4, timeS.toInt())
            int(23, precisionBits.toLong())
        }.done()
    }

    /** `Data`: the application port and its payload. */
    fun data(port: Int, payload: ByteArray, wantResponse: Boolean = false, requestId: Int = 0): ByteArray = Pb().apply {
        int(1, port.toLong())
        bytes(2, payload)
        if (wantResponse) int(3, 1)
        if (requestId != 0) fixed32(6, requestId)
    }.done()

    /** `RouteDiscovery` of a traceroute reply: the nodes the request went through, as it carried them. */
    fun routeDiscovery(towards: List<Int>): ByteArray = Pb().apply {
        if (towards.isNotEmpty()) bytes(1, ByteArray(towards.size * 4).also { b -> towards.forEachIndexed { i, n -> for (k in 0 until 4) b[4 * i + k] = (n ushr (8 * k)).toByte() } })
    }.done()

    /** `Routing` with no error: the payload of an acknowledgement. */
    fun routingAck(): ByteArray = Pb().apply { int(3, 0) }.done()

    /** A whole packet (a broadcast unless [to] is set) from [from] on the channel ([channelName], [key]): header, then encrypted [data]. */
    fun packet(from: Int, id: Int, channelName: String, key: ByteArray, data: ByteArray, hopLimit: Int = 3, to: Int = Meshtastic.BROADCAST): ByteArray {
        val h = ByteArray(16)
        fun le32(o: Int, v: Int) { for (i in 0 until 4) h[o + i] = (v ushr (8 * i)).toByte() }
        le32(0, to)
        le32(4, from)
        le32(8, id)
        h[12] = ((hopLimit and 7) or ((hopLimit and 7) shl 5)).toByte() // hop limit, hop start
        h[13] = Meshtastic.channelHash(channelName, key).toByte()
        h[14] = 0 // next hop: none (flooding)
        h[15] = from.toByte() // relay node: ourselves
        return h + Meshtastic.crypt(key, from, id, data)
    }
}
