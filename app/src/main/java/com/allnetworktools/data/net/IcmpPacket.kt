package com.allnetworktools.data.net

/** ICMP echo packets as an unprivileged app sends them: on a "ping" datagram socket, where the kernel fills in id and checksum. */
object IcmpPacket {
    const val ECHO_REQUEST = 8
    const val ECHO_REPLY = 0
    const val ECHO6_REQUEST = 128
    const val ECHO6_REPLY = 129

    /** An echo request of [payload] data bytes; the kernel sets the identifier and the checksum on ping sockets. */
    fun echoRequest(v6: Boolean, seq: Int, payload: Int): ByteArray {
        val b = ByteArray(8 + payload)
        b[0] = (if (v6) ECHO6_REQUEST else ECHO_REQUEST).toByte()
        b[6] = (seq shr 8).toByte(); b[7] = seq.toByte()
        for (i in 0 until payload) b[8 + i] = (i and 0xFF).toByte()
        return b
    }

    /** True when [buf] holds an echo reply (not an error message) for sequence [seq]. */
    fun isReply(v6: Boolean, buf: ByteArray, len: Int, seq: Int): Boolean =
        len >= 8 && (buf[0].toInt() and 0xFF) == (if (v6) ECHO6_REPLY else ECHO_REPLY) &&
            (((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)) == seq
}
