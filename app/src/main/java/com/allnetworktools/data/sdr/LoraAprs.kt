package com.allnetworktools.data.sdr

/**
 * LoRa APRS (richonguzman/LoRa_APRS_iGate and lora-aprs/LoRa_APRS_iGate, as described in OnAir's mesh_proto_aprs): an
 * APRS packet in TNC2 text ("SRC>DEST,PATH:info") after the 3-byte prefix "<\xFF\x01", 125 kHz LoRa, sync word 0x12,
 * CRC on. Europe uses 433.775 MHz at SF12 (CR 4/5); Poland 434.855 MHz at SF9 (CR 4/7).
 */
object LoraAprs {
    const val SYNC_WORD = 0x12

    /** Both European channels from one tuning: 2 MS/s centred between them. */
    val EU = MeshBand(
        434_315_000L, wide = true,
        listOf(
            MeshChannel(433_775_000L, 125_000.0, listOf(12)),
            MeshChannel(434_855_000L, 125_000.0, listOf(9)),
        ),
    )

    /** The packet as an AX.25-like frame the APRS decoder reads; null when it is not LoRa APRS. */
    fun parse(p: ByteArray): Ax25Frame? {
        var o = 0
        // Most trackers send the prefix; a few old ones send the bare TNC2 line.
        if (p.size >= 3 && p[0].toInt() == 0x3C && (p[1].toInt() and 0xFF) == 0xFF && p[2].toInt() == 0x01) o = 3
        val text = String(p, o, p.size - o, Charsets.ISO_8859_1)
        val gt = text.indexOf('>')
        val colon = text.indexOf(':')
        if (gt !in 1..9 || colon <= gt + 1) return null
        val src = text.substring(0, gt)
        if (!src.all { it.isLetterOrDigit() || it == '-' }) return null
        val addr = text.substring(gt + 1, colon).split(',')
        if (addr.any { a -> a.isEmpty() || a.length > 10 || !a.all { it.isLetterOrDigit() || it == '-' || it == '*' } }) return null
        val info = p.copyOfRange(o + colon + 1, p.size)
        if (info.isEmpty()) return null
        return Ax25Frame(src, addr[0], addr.drop(1), info)
    }
}
