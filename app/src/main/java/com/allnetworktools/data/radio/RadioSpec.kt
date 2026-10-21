package com.allnetworktools.data.radio

import com.allnetworktools.R
import com.allnetworktools.util.S

/** The radio did not answer as expected; the message is meant for the user. */
open class RadioException(message: String) : Exception(message)

/** Nothing answered the handshake of this model: it is probably another one. */
class RadioNoAnswer(message: String) : RadioException(message)

/** What a memory list has to respect for a radio: how many memories, how long the names, which bands and power levels. */
interface RadioLimits {
    val label: String
    val slots: Int
    val nameLength: Int

    /** Frequencies the radio can receive, in Hz. */
    val bands: List<LongRange>

    /** Power levels the radio offers, strongest first. */
    val powers: List<RadioPower>

    fun inBand(hz: Long) = bands.any { hz in it }

    /** The channel as the radio will store it: 10 Hz steps, capital letters that fit the name, a power it has. */
    fun normalize(ch: RadioChannel): RadioChannel = ch.copy(
        rxHz = ch.rxHz / 10 * 10,
        txHz = ch.txHz?.let { it / 10 * 10 },
        name = ch.name.uppercase().filter { it.code in 32..126 }.take(nameLength).trimEnd(),
        power = if (ch.power in powers) ch.power else powers.last(),
    )
}

/** Before a radio is recognised: the most generous limits, so a list can be prepared and then fitted to whatever answers. */
object GenericRadio : RadioLimits {
    override val label = S(R.string.handheld_radio)
    override val slots = 256
    override val nameLength = 12
    override val bands = listOf(18_000_000L..1_000_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Medium, RadioPower.Low)
}

/**
 * A handheld that can be read and written through its programming cable: the clone protocol and the layout of its channel
 * memory. Only the channels are touched: every other byte of the radio's memory is left exactly as it was read.
 */
interface RadioSpec : RadioLimits {
    val id: String
    val baud: Int

    /** The radio's memory image, as downloaded; [decode] and [encode] work on it. */
    val imageSize: Int

    fun decode(image: ByteArray, slot: Int): RadioChannel?

    fun encode(image: ByteArray, ch: RadioChannel)

    fun erase(image: ByteArray, slot: Int)

    /** Reads the memory image. Returns it with whether this firmware is one the app knows (writing is refused otherwise). */
    fun download(link: SerialLink, progress: (Float) -> Unit): Download

    /** Writes back the memory blocks that hold [slots]; [image] is the one from [download] with the edited channels encoded. */
    fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit)

    class Download(val image: ByteArray, val ident: String, val known: Boolean)
}

internal fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

internal fun SerialLink.readExactly(count: Int, timeoutMs: Int, what: String): ByteArray =
    read(count, timeoutMs).also { if (it.size != count) throw RadioException(S(R.string.the_radio_did_not_answer, what)) }

/**
 * Baofeng UV-5R and its clones (BFB291 and earlier firmwares): 9600 baud, 128 memories of 16 bytes at 0x0000 and their
 * 7-character names at 0x1000. Layout and handshake follow the open clone-mode description used by CHIRP.
 */
class Uv5rSpec(private val pauseMs: Long = 50) : RadioSpec {
    override val id = "uv5r"
    override val label = "Baofeng UV-5R"
    override val baud = 9600
    override val slots = 128
    override val nameLength = 7
    override val bands = listOf(130_000_000L..180_000_000L, 400_000_000L..520_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Low)
    override val imageSize = 0x1800

    private fun pause() { if (pauseMs > 0) Thread.sleep(pauseMs) }

    override fun decode(image: ByteArray, slot: Int): RadioChannel? {
        val o = slot * REC
        if (image[o] == 0xFF.toByte()) return null
        val rx = (Bcd.decode(image, o) ?: return null) * 10
        val txRaw = Bcd.decode(image, o + 4)
        val b14 = image[o + 14].toInt()
        val b15 = image[o + 15].toInt()
        return RadioChannel(
            slot = slot, rxHz = rx, txHz = txRaw?.let { it * 10 },
            name = image.asciiName(NAMES + slot * REC, nameLength),
            rxTone = RadioTones.decode(image.u16(o + 8)), txTone = RadioTones.decode(image.u16(o + 10)),
            power = if (b14 and 3 == 0) RadioPower.High else RadioPower.Low,
            wide = b15 and 0x40 != 0, scan = b15 and 0x04 != 0,
        )
    }

    override fun encode(image: ByteArray, ch: RadioChannel) {
        val o = ch.slot * REC
        // A memory that already exists keeps its other bits (PTT-ID, busy-channel lockout…).
        if (image[o] == 0xFF.toByte()) image.fill(0, o, o + REC)
        Bcd.encode(ch.rxHz / 10, image, o)
        if (ch.txHz == null) image.fill(0xFF.toByte(), o + 4, o + 8) else Bcd.encode(ch.txHz / 10, image, o + 4)
        image.putU16(o + 8, RadioTones.encode(ch.rxTone))
        image.putU16(o + 10, RadioTones.encode(ch.txTone))
        image[o + 14] = ((image[o + 14].toInt() and 0xFC) or if (ch.power == RadioPower.High) 0 else 1).toByte()
        var b15 = image[o + 15].toInt() and 0xFF
        b15 = if (ch.wide) b15 or 0x40 else b15 and 0x40.inv()
        b15 = if (ch.scan) b15 or 0x04 else b15 and 0x04.inv()
        image[o + 15] = b15.toByte()
        image.putAsciiName(NAMES + ch.slot * REC, nameLength, ch.name)
    }

    override fun erase(image: ByteArray, slot: Int) {
        image.fill(0xFF.toByte(), slot * REC, slot * REC + REC)
        image.fill(0xFF.toByte(), NAMES + slot * REC, NAMES + slot * REC + REC)
    }

    private fun identify(link: SerialLink): String {
        var last = S(R.string.no_answer)
        for (magic in MAGICS) {
            link.purge()
            for (b in magic) { link.write(byteArrayOf(b)); if (pauseMs > 0) Thread.sleep(10) }
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) { last = if (ack.isEmpty()) S(R.string.no_answer) else S(R.string.answer, ack.hex()); pause(); continue }
            link.write(byteArrayOf(0x02))
            // The ident ends with 0xDD; some firmwares send 12 bytes instead of 8.
            val resp = java.io.ByteArrayOutputStream()
            for (i in 0 until 12) {
                val b = link.read(1, 1000)
                if (b.isEmpty()) break
                resp.write(b[0].toInt())
                if (b[0] == 0xDD.toByte()) break
            }
            val ident = resp.toByteArray()
            if (ident.size != 8 && ident.size != 12) throw RadioException(S(R.string.unexpected_radio_identification, ident.hex()))
            link.write(byteArrayOf(ACK))
            val ack2 = link.read(1, 1000)
            if (ack2.size != 1 || ack2[0] != ACK) throw RadioException(S(R.string.the_radio_refused_clone_mode))
            return ident.hex()
        }
        throw RadioNoAnswer(
            S(R.string.the_radio_does_not_answer_check, last) +
                S(R.string.and_that_its_volume_is_not),
        )
    }

    private fun readBlock(link: SerialLink, addr: Int, size: Int, first: Boolean): ByteArray {
        link.write(byteArrayOf('S'.code.toByte(), (addr shr 8).toByte(), addr.toByte(), size.toByte()))
        if (!first) {
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException(S(R.string.the_radio_refused_to_send_block).format(addr))
        }
        val head = link.readExactly(4, 1000, S(R.string.block).format(addr))
        if (head[0] != 'X'.code.toByte() || (((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)) != addr || head[3].toInt() != size) {
            throw RadioException(S(R.string.unexpected_answer_from_the_radio_for).format(addr))
        }
        val data = link.readExactly(size, 1000, S(R.string.block).format(addr))
        link.write(byteArrayOf(ACK))
        pause()
        return data
    }

    override fun download(link: SerialLink, progress: (Float) -> Unit): RadioSpec.Download {
        val ident = identify(link)
        // The first block is read once and dropped: the radio answers it without the usual leading acknowledgement.
        readBlock(link, 0, BLOCK, true)
        val image = ByteArray(imageSize)
        for (a in 0 until imageSize step BLOCK) {
            readBlock(link, a, BLOCK, false).copyInto(image, a)
            progress((a + BLOCK).toFloat() / imageSize)
        }
        return RadioSpec.Download(image, ident, known = true)
    }

    override fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit) {
        identify(link)
        val regions = slots.sorted().flatMap { listOf(it * REC, NAMES + it * REC) }
        regions.forEachIndexed { i, a ->
            link.write(byteArrayOf('X'.code.toByte(), (a shr 8).toByte(), a.toByte(), REC.toByte()) + image.copyOfRange(a, a + REC))
            pause()
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException(S(R.string.the_radio_refused_block_nothing_more).format(a))
            progress((i + 1f) / regions.size)
        }
    }

    private companion object {
        const val REC = 16
        const val NAMES = 0x1000
        const val BLOCK = 0x40
        const val ACK = 0x06.toByte()
        val MAGICS = listOf(
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25), // BFB291 and later
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x01, 0x25, 0x98.toByte(), 0x4D), // original firmware
        )
    }
}

/**
 * Radtel RT-470X (JC-8810 family): 57600 baud, 256 memories of 32 bytes at 0x0000 including their 12-character names.
 * Reading works with any firmware; writing only with the firmware identifications listed below.
 */
class Rt470xSpec(private val pauseMs: Long = 0) : RadioSpec {
    override val id = "rt470x"
    override val label = "Radtel RT-470X"
    override val baud = 57600
    override val slots = 256
    override val nameLength = 12
    override val bands = listOf(18_000_000L..1_000_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Medium, RadioPower.Low)
    override val imageSize = 0x2000

    override fun decode(image: ByteArray, slot: Int): RadioChannel? {
        val o = slot * REC
        if (image[o] == 0xFF.toByte()) return null
        val rx = (Bcd.decode(image, o) ?: return null) * 10
        val txRaw = Bcd.decode(image, o + 4)
        val power = when (image[o + 14].toInt() and 3) { 0 -> RadioPower.High; 2 -> RadioPower.Medium; else -> RadioPower.Low }
        val b15 = image[o + 15].toInt()
        return RadioChannel(
            slot = slot, rxHz = rx, txHz = txRaw?.let { it * 10 }, name = image.asciiName(o + 20, nameLength),
            rxTone = RadioTones.decode(image.u16(o + 8)), txTone = RadioTones.decode(image.u16(o + 10)),
            power = power, wide = b15 and 0x40 == 0, scan = b15 and 0x04 != 0,
        )
    }

    override fun encode(image: ByteArray, ch: RadioChannel) {
        val o = ch.slot * REC
        if (image[o] == 0xFF.toByte()) {
            image.fill(0, o, o + 16)
            image.fill(0xFF.toByte(), o + 16, o + REC)
        }
        Bcd.encode(ch.rxHz / 10, image, o)
        if (ch.txHz == null) image.fill(0xFF.toByte(), o + 4, o + 8) else Bcd.encode(ch.txHz / 10, image, o + 4)
        image.putU16(o + 8, RadioTones.encode(ch.rxTone))
        image.putU16(o + 10, RadioTones.encode(ch.txTone))
        val p = when (ch.power) { RadioPower.High -> 0; RadioPower.Medium -> 2; RadioPower.Low -> 1 }
        image[o + 14] = ((image[o + 14].toInt() and 0xFC) or p).toByte()
        var b15 = image[o + 15].toInt() and 0xFF
        b15 = if (ch.wide) b15 and 0x40.inv() else b15 or 0x40
        b15 = if (ch.scan) b15 or 0x04 else b15 and 0x04.inv()
        image[o + 15] = b15.toByte()
        image.putAsciiName(o + 20, nameLength, ch.name)
    }

    override fun erase(image: ByteArray, slot: Int) = image.fill(0xFF.toByte(), slot * REC, slot * REC + REC)

    private fun identify(link: SerialLink): Pair<String, Boolean> {
        var ok = false
        for (attempt in 0 until 5) {
            link.purge()
            link.write(MAGIC)
            val ack = link.read(1, 1000)
            if (ack.size == 1 && ack[0] == ACK) { ok = true; break }
            if (pauseMs > 0) Thread.sleep(pauseMs)
        }
        if (!ok) {
            throw RadioNoAnswer(
                S(R.string.the_radio_does_not_answer_check_2) +
                    S(R.string.then_turn_it_off_and_on),
            )
        }
        link.write(byteArrayOf('F'.code.toByte()))
        val ident = link.readExactly(8, 1000, "identification")
        return ident.hex() to FINGERPRINTS.any { it.contentEquals(ident) }
    }

    private fun readBlock(link: SerialLink, addr: Int, size: Int): ByteArray {
        val cmd = byteArrayOf('R'.code.toByte(), (addr shr 8).toByte(), addr.toByte(), size.toByte())
        link.write(cmd)
        val resp = link.readExactly(4 + size, 1000, S(R.string.block).format(addr))
        if (!resp.copyOf(4).contentEquals(cmd)) throw RadioException(S(R.string.unexpected_answer_from_the_radio_for).format(addr))
        return resp.copyOfRange(4, resp.size)
    }

    override fun download(link: SerialLink, progress: (Float) -> Unit): RadioSpec.Download {
        val (ident, known) = identify(link)
        val image = ByteArray(imageSize)
        for (a in 0 until imageSize step BLOCK) {
            readBlock(link, a, BLOCK).copyInto(image, a)
            progress((a + BLOCK).toFloat() / imageSize)
        }
        runCatching { link.write(byteArrayOf('E'.code.toByte())) }
        return RadioSpec.Download(image, ident, known)
    }

    override fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit) {
        val (ident, known) = identify(link)
        if (!known) throw RadioException(S(R.string.unrecognised_firmware_writing_is_refused_for, ident))
        // Whole 64-byte blocks, as the manufacturer's software writes them: two memories each.
        val blocks = slots.map { it * REC / BLOCK * BLOCK }.distinct().sorted()
        blocks.forEachIndexed { i, a ->
            val cmd = byteArrayOf('W'.code.toByte(), (a shr 8).toByte(), a.toByte(), BLOCK.toByte())
            link.write(cmd + image.copyOfRange(a, a + BLOCK))
            val ack = link.read(1, 1000)
            if (ack.size != 1 || ack[0] != ACK) throw RadioException(S(R.string.the_radio_refused_block_nothing_more).format(a))
            progress((i + 1f) / blocks.size)
        }
        runCatching { link.write(byteArrayOf('E'.code.toByte())) }
    }

    private companion object {
        const val REC = 32
        const val BLOCK = 0x40
        const val ACK = 0x06.toByte()
        val MAGIC = "PROGRAMJC81U".toByteArray(Charsets.US_ASCII)

        /** Firmware identifications of the RT-470X (original board v1.18A, second board v2.10A and v2.13A). */
        val FINGERPRINTS = listOf(
            byteArrayOf(0, 0, 0, 0x20, 0, 0x20, 0xCC.toByte(), 0x04),
            byteArrayOf(0, 0, 0, 0x2C, 0, 0x20, 0xD8.toByte(), 0x04),
            byteArrayOf(0, 0, 0, 0x36, 0, 0x20, 0xDC.toByte(), 0x04),
        )
    }
}

/**
 * Quansheng UV-K5 family (UV-K5, UV-K5(8), UV-K6, UV-5R Plus), stock firmware 2.01.x and the modified firmwares built on it:
 * 38400 baud, packets `AB CD len 00 · payload+CRC XOR-obfuscated · DC BA` as described by CHIRP's uvk5 driver; 200
 * memories of 16 bytes at 0x0000, their attributes at 0x0D60 and 16-byte names at 0x0F50. Only those three areas are
 * written back, by whole 8-byte EEPROM pages, never the calibration at the end of the memory.
 */
class UvK5Spec : RadioSpec {
    override val id = "uvk5"
    override val label = "Quansheng UV-K5 / UV-K6"
    override val baud = 38400
    override val slots = 200
    override val nameLength = 10
    override val bands = listOf(50_000_000L..76_000_000L, 108_000_000L..600_000_000L)
    override val powers = listOf(RadioPower.High, RadioPower.Medium, RadioPower.Low)
    override val imageSize = 0x2000

    private fun u32(b: ByteArray, o: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }

    private fun tone(flag: Int, code: Int): Tone = when (flag) {
        1 -> RadioTones.ctcss.getOrNull(code)?.let { Tone.Ctcss(it) } ?: Tone.None
        2, 3 -> Dcs.getOrNull(code)?.let { Tone.Dcs(it, flag == 3) } ?: Tone.None
        else -> Tone.None
    }

    /** Flag (0 none, 1 CTCSS, 2 DCS, 3 DCS inverted) and index, the way the radio stores a tone. */
    private fun toneCode(t: Tone): Pair<Int, Int> = when (t) {
        Tone.None -> 0 to 0
        is Tone.Ctcss -> RadioTones.ctcss.indexOfFirst { kotlin.math.abs(it - t.hz) < 0.05 }.let { if (it < 0) 0 to 0 else 1 to it }
        is Tone.Dcs -> Dcs.indexOf(t.code).let { if (it < 0) 0 to 0 else (if (t.inverted) 3 else 2) to it }
    }

    override fun decode(image: ByteArray, slot: Int): RadioChannel? {
        val o = slot * REC
        val attr = image[ATTR + slot].toInt() and 0xFF
        val raw = u32(image, o)
        if (raw == 0xFFFFFFFFL || raw == 0L || attr and 0x08 != 0) return null
        val rx = raw * 10
        val offset = u32(image, o + 4) * 10
        val tx = when (image[o + 11].toInt() and 0x0F) { 1 -> rx + offset; 2 -> rx - offset; else -> rx }
        val flags = image[o + 10].toInt() and 0xFF
        val b12 = image[o + 12].toInt() and 0xFF
        val name = (0 until 16).map { image[NAMES + slot * REC + it].toInt() and 0xFF }.takeWhile { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        return RadioChannel(
            slot, rx, tx.takeIf { it < LISTEN_ONLY_TX }, name,
            rxTone = tone(flags and 0x0F, image[o + 8].toInt() and 0xFF),
            txTone = tone(flags shr 4, image[o + 9].toInt() and 0xFF),
            power = when ((b12 shr 2) and 3) { 2 -> RadioPower.High; 1 -> RadioPower.Medium; else -> RadioPower.Low },
            wide = b12 and 0x02 == 0,
            scan = attr and 0x80 != 0,
        )
    }

    override fun encode(image: ByteArray, ch: RadioChannel) {
        val o = ch.slot * REC
        val fresh = u32(image, o) == 0xFFFFFFFFL
        put32(image, o, ch.rxHz / 10)
        // A listen-only memory gets a transmit frequency no UV-K5 can use (above 1 GHz): the radio refuses to transmit.
        val tx = ch.txHz ?: LISTEN_ONLY_TX
        val diff = tx - ch.rxHz
        put32(image, o + 4, kotlin.math.abs(diff) / 10)
        val (rxFlag, rxCode) = toneCode(ch.rxTone)
        val (txFlag, txCode) = toneCode(ch.txTone)
        image[o + 8] = rxCode.toByte()
        image[o + 9] = txCode.toByte()
        image[o + 10] = ((txFlag shl 4) or rxFlag).toByte()
        val modulation = if (fresh) 0 else ((image[o + 11].toInt() shr 4) and 0x0F).takeIf { it <= 2 } ?: 0
        image[o + 11] = ((modulation shl 4) or (if (diff > 0) 1 else if (diff < 0) 2 else 0)).toByte()
        val p = when (ch.power) { RadioPower.High -> 2; RadioPower.Medium -> 1; RadioPower.Low -> 0 }
        image[o + 12] = ((p shl 2) or (if (ch.wide) 0 else 0x02)).toByte()
        image[o + 13] = 0
        if (fresh || (image[o + 14].toInt() and 0xFF) > 6) image[o + 14] = 4 // 12.5 kHz step
        image[o + 15] = 0
        val name = ch.name.take(nameLength).toByteArray(Charsets.US_ASCII)
        for (i in 0 until REC) image[NAMES + ch.slot * REC + i] = if (i < name.size) name[i] else 0
        val oldAttr = image[ATTR + ch.slot].toInt() and 0xFF
        val compander = if (oldAttr and 0x08 != 0) 0 else oldAttr and 0x30
        image[ATTR + ch.slot] = ((if (ch.scan) 0x80 else 0) or compander or band(ch.rxHz)).toByte()
    }

    override fun erase(image: ByteArray, slot: Int) {
        image.fill(0xFF.toByte(), slot * REC, slot * REC + REC)
        image.fill(0xFF.toByte(), NAMES + slot * REC, NAMES + slot * REC + REC)
        image[ATTR + slot] = 0x0F // free, band 7
    }

    /** Band index of the radio's frequency table. */
    private fun band(hz: Long): Int = when {
        hz < 76_000_000L -> 0
        hz < 136_000_000L -> 1
        hz < 174_000_000L -> 2
        hz < 350_000_000L -> 3
        hz < 400_000_000L -> 4
        hz < 470_000_000L -> 5
        else -> 6
    }

    // ---- protocol ------------------------------------------------------------------------------

    private fun send(link: SerialLink, payload: ByteArray) {
        val crc = UvK5Packet.crc(payload)
        val body = UvK5Packet.xor(payload + byteArrayOf(crc.toByte(), (crc shr 8).toByte()))
        link.write(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), payload.size.toByte(), 0) + body + byteArrayOf(0xDC.toByte(), 0xBA.toByte()))
    }

    private fun receive(link: SerialLink, what: String, silentIsNoAnswer: Boolean = false): ByteArray {
        val head = link.read(4, 1500)
        if (head.isEmpty() && silentIsNoAnswer) throw RadioNoAnswer(S(R.string.the_radio_does_not_answer))
        if (head.size != 4 || head[0] != 0xAB.toByte() || head[1] != 0xCD.toByte() || head[3].toInt() != 0) {
            if (silentIsNoAnswer) throw RadioNoAnswer(S(R.string.unexpected_answer, head.hex()))
            throw RadioException(S(R.string.unexpected_answer_from_the_radio, what))
        }
        val body = link.readExactly(head[2].toInt() and 0xFF, 1500, what)
        val foot = link.readExactly(4, 1500, what)
        if (foot[2] != 0xDC.toByte() || foot[3] != 0xBA.toByte()) throw RadioException(S(R.string.incomplete_frame_from_the_radio, what))
        return UvK5Packet.xor(body)
    }

    private fun hello(link: SerialLink): Pair<String, Boolean> {
        link.purge()
        send(link, byteArrayOf(0x14, 0x05, 0x04, 0x00) + SESSION)
        val r = receive(link, "identification", silentIsNoAnswer = true)
        if (r.isEmpty() || r[0].toInt() != 0x15) throw RadioNoAnswer(S(R.string.this_is_not_a_uv_k5))
        val fw = r.drop(4).takeWhile { it.toInt() != 0 }.map { (it.toInt() and 0xFF).toChar() }.joinToString("").trim()
        return fw to ("2.01." in fw)
    }

    private fun readBlock(link: SerialLink, addr: Int, size: Int): ByteArray {
        send(link, byteArrayOf(0x1B, 0x05, 0x08, 0x00, addr.toByte(), (addr shr 8).toByte(), size.toByte(), 0) + SESSION)
        val r = receive(link, S(R.string.block).format(addr))
        if (r.size < 8 + size || r[0].toInt() != 0x1C) throw RadioException(S(R.string.unexpected_answer_from_the_radio_for).format(addr))
        return r.copyOfRange(8, 8 + size)
    }

    override fun download(link: SerialLink, progress: (Float) -> Unit): RadioSpec.Download {
        val (fw, known) = hello(link)
        val image = ByteArray(imageSize)
        for (a in 0 until imageSize step BLOCK) {
            readBlock(link, a, BLOCK).copyInto(image, a)
            progress((a + BLOCK).toFloat() / imageSize)
        }
        return RadioSpec.Download(image, fw.ifEmpty { "UV-K5" }, known)
    }

    override fun upload(link: SerialLink, image: ByteArray, slots: Collection<Int>, progress: (Float) -> Unit) {
        val (fw, known) = hello(link)
        if (!known) throw RadioException(S(R.string.unrecognised_firmware_writing_is_refused_for, fw))
        val pages = slots.sorted().flatMap { s -> listOf(s * REC to REC, NAMES + s * REC to REC, ATTR + s / 8 * 8 to 8) }.distinct()
        pages.forEachIndexed { i, (a, n) ->
            send(link, byteArrayOf(0x1D, 0x05, (n + 8).toByte(), 0, a.toByte(), (a shr 8).toByte(), n.toByte(), 1) + SESSION + image.copyOfRange(a, a + n))
            val r = receive(link, S(R.string.write).format(a))
            if (r.size < 6 || r[0].toInt() != 0x1E || r[4] != a.toByte() || r[5] != (a shr 8).toByte()) {
                throw RadioException(S(R.string.the_radio_refused_block_nothing_more).format(a))
            }
            progress((i + 1f) / pages.size)
        }
        // The radio reads its memory at start-up: restart it so the new channels show.
        send(link, byteArrayOf(0xDD.toByte(), 0x05, 0x00, 0x00))
    }

    private companion object {
        const val REC = 16
        const val ATTR = 0x0D60
        const val NAMES = 0x0F50
        const val BLOCK = 0x80
        const val LISTEN_ONLY_TX = 1_000_000_000L
        val SESSION = byteArrayOf(0x6A, 0x39, 0x57, 0x64)

        /** The firmware's 104 digital codes (no 645), in its order. */
        val Dcs = RadioTones.dcs.filter { it != 645 }
    }
}

/** Packet helpers of the UV-K5 protocol: XOR obfuscation with a 16-byte key, CRC-16/XMODEM of the plain payload. */
object UvK5Packet {
    private val KEY = intArrayOf(0x16, 0x6C, 0x14, 0xE6, 0x2E, 0x91, 0x0D, 0x40, 0x21, 0x35, 0xD5, 0x40, 0x13, 0x03, 0xE9, 0x80)

    fun xor(d: ByteArray): ByteArray = ByteArray(d.size) { (d[it].toInt() xor KEY[it % KEY.size]).toByte() }

    fun crc(d: ByteArray): Int {
        var c = 0
        for (b in d) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
            c = c and 0xFFFF
        }
        return c
    }
}

object RadioSpecs {
    fun all(): List<RadioSpec> = listOf(Uv5rSpec(), Rt470xSpec(), UvK5Spec())
}
