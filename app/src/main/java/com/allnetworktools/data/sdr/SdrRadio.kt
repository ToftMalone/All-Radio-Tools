package com.allnetworktools.data.sdr

/** What a radio can do; the tools check it before asking for a frequency or a rate. */
data class SdrCaps(
    val name: String,
    val minHz: Long,
    val maxHz: Long,
    /** Highest sample rate the driver offers, in samples per second. */
    val maxRate: Int,
)

/**
 * A receive-only software defined radio on the USB bus, whatever its make. Every driver hands the tools the same thing:
 * interleaved signed 8-bit I/Q at the requested rate, so the decoders do not care which radio is plugged in.
 */
interface SdrRadio {
    val caps: SdrCaps

    /** [filterHz] is the baseband filter a HackRF wants; other radios choose theirs. */
    fun setSampleRate(hz: Int, filterHz: Int): Boolean
    fun setFrequency(hz: Long): Boolean

    /** The gains use the HackRF scale (LNA 0-40 dB, VGA 0-62 dB, amplifier 14 dB); drivers map it to their own. */
    fun setLnaGain(db: Int): Boolean
    fun setVgaGain(db: Int): Boolean
    fun setAmp(on: Boolean): Boolean

    /** Power on the antenna port (bias tee), when the radio has one. */
    fun setAntennaPower(on: Boolean): Boolean

    /** The radio's own gain, in the steps of [SdrKind.gainSteps]; false for a radio that has none (the HackRF). */
    fun setNativeGain(value: Int): Boolean = false

    /** [onSamples] gets each buffer on a dedicated thread; [onError] fires once if the radio stops answering. */
    fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit)
    fun close()
}

/** The most bytes of samples a driver hands the tools at once: the size of the buffers they prepare. */
const val SDR_MAX_CHUNK = 131_072

/**
 * Hands [len] bytes of [buf] to [sink] in pieces of at most [SDR_MAX_CHUNK] bytes, each a whole number of I/Q pairs.
 * A driver whose USB transfers are bigger than the tools' buffers (an RTL-SDR reads 256 KB at a time) goes through it.
 */
fun deliverInChunks(buf: ByteArray, len: Int, sink: (ByteArray, Int) -> Unit) {
    if (len <= SDR_MAX_CHUNK) { sink(buf, len); return }
    var off = 0
    val piece = ByteArray(SDR_MAX_CHUNK)
    while (off < len) {
        val n = minOf(SDR_MAX_CHUNK, len - off) and 1.inv()
        if (n <= 0) break
        System.arraycopy(buf, off, piece, 0, n)
        sink(piece, n)
        off += n
    }
}
