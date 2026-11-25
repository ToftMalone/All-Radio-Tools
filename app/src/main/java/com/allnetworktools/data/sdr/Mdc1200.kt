package com.allnetworktools.data.sdr

/**
 * Motorola MDC-1200: the 1200 bit/s burst a Motorola radio sends on an analogue FM channel when its key is pressed (and
 * often released), carrying its unit ID and an operation (PTT ID, emergency, status, call alert...). Same tones as
 * FleetSync (1200 and 1800 Hz) but XOR-precoded: a 1200 Hz bit means the data bit is the same as the one before, an
 * 1800 Hz bit that it changed. After the sync word 0x07092A446F, 112 bits interleaved over 16 x 7, packed LSB first:
 * op, arg, unit ID (two bytes), CRC-16 of those four (low byte first), then redundancy.
 *
 * Format facts from the description in GopherTrunk by Matt Cheramie (Apache-2.0, docs/mdc1200.md and
 * internal/radio/mdc1200); written afresh here. Checked against a real Motorola capture and the reference modem's
 * audio that project publishes.
 */
object Mdc1200 {
    const val SYNC = 0x07092A446FL
    const val FRAME_BITS = 112

    data class Message(val op: Int, val arg: Int, val unit: Int) {
        val unitHex: String get() = "%04X".format(unit)
    }

    /** What an op/arg pair means, for the common Motorola operations; null for the others (shown raw). */
    fun label(op: Int, arg: Int): Operation? = when (op) {
        0x01 -> if (arg == 0x00) Operation.PttEnd else Operation.Ptt
        0x00 -> Operation.Emergency
        0x06 -> Operation.StatusRequest
        0x12 -> Operation.Status
        0x2B -> Operation.Inhibit
        0x2C -> Operation.Enable
        0x22 -> Operation.Message
        0x35 -> Operation.SelectiveCall
        0x46 -> Operation.RemoteMonitor
        0x63 -> Operation.RadioCheck
        0x0A -> Operation.CallAlert
        else -> null
    }

    enum class Operation { Ptt, PttEnd, Emergency, StatusRequest, Status, Inhibit, Enable, Message, SelectiveCall, RemoteMonitor, RadioCheck, CallAlert }

    fun crc(data: IntArray, n: Int = 4): Int {
        var c = 0
        for (i in 0 until n) {
            c = c xor (data[i] and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
        }
        return c.inv() and 0xFFFF
    }

    /** The 112 bits after the sync word as a message, when its CRC checks out. */
    fun decode(bits: IntArray): Message? {
        if (bits.size < FRAME_BITS) return null
        val data = IntArray(14)
        var idx = 0
        for (i in 0 until 16) for (j in 0 until 7) {
            if (bits[j * 16 + i] and 1 != 0) data[idx / 8] = data[idx / 8] or (1 shl (idx % 8))
            idx++
        }
        val rx = data[4] or (data[5] shl 8)
        if (crc(data) != rx) return null
        return Message(data[0], data[1], (data[2] shl 8) or data[3])
    }

    // ---- Encoder, for the tests --------------------------------------------------------------------------------

    /** A whole burst as data bits (before precoding): 7 bytes of 0x55 leader, the sync word, the block. */
    fun burst(op: Int, arg: Int, unit: Int): IntArray {
        val data = IntArray(14)
        data[0] = op; data[1] = arg; data[2] = (unit ushr 8) and 0xFF; data[3] = unit and 0xFF
        val c = crc(data)
        data[4] = c and 0xFF; data[5] = c ushr 8
        // Redundancy: a convolutional parity of the first seven bytes (the decoder here does not need it).
        val csr = IntArray(7)
        for (i in 0 until 7) for (j in 0 until 8) {
            for (k in 6 downTo 1) csr[k] = csr[k - 1]
            csr[0] = (data[i] ushr j) and 1
            data[7 + i] = data[7 + i] or ((csr[0] xor csr[2] xor csr[5] xor csr[6]) shl j)
        }
        val l = IntArray(FRAME_BITS) { (data[it / 8] ushr (it % 8)) and 1 }
        val block = IntArray(FRAME_BITS).also { b -> for (k in 0 until FRAME_BITS) b[(k % 7) * 16 + k / 7] = l[k] }
        val out = ArrayList<Int>()
        repeat(56) { out += it and 1 }
        for (i in 39 downTo 0) out += ((SYNC ushr i) and 1).toInt()
        block.forEach { out += it }
        return out.toIntArray()
    }

    /** Data bits to the tones sent: 1 (1200 Hz) when the bit repeats, 0 (1800 Hz) when it changes. */
    fun precode(data: IntArray): IntArray { var prev = 0; return IntArray(data.size) { val b = data[it]; val t = if (b == prev) 1 else 0; prev = b; t } }
}

/**
 * The MDC-1200 sync hunt on tone decisions (1 for 1200 Hz): the data bits are their running XOR, whose start is unknown,
 * so the sync word or its complement (four wrong bits at most) starts a block, read inverted in the second case.
 */
class Mdc1200Framer(private val onMessage: (Mdc1200.Message) -> Unit) {
    private var data = 0
    private var reg = 0L
    private var capturing = false
    private var invert = 0
    private val block = IntArray(Mdc1200.FRAME_BITS)
    private var n = 0

    fun tone(t: Int) {
        // A 1800 Hz bit (t = 0) flips the data bit.
        data = data xor (1 - (t and 1))
        if (capturing) {
            block[n++] = data xor invert
            if (n == block.size) { capturing = false; Mdc1200.decode(block)?.let(onMessage) }
            return
        }
        reg = ((reg shl 1) or data.toLong()) and 0xFF_FFFF_FFFFL
        invert = when {
            java.lang.Long.bitCount(reg xor Mdc1200.SYNC) <= 4 -> 0
            java.lang.Long.bitCount(reg xor (Mdc1200.SYNC xor 0xFF_FFFF_FFFFL)) <= 4 -> 1
            else -> return
        }
        capturing = true
        n = 0
    }
}
