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

    /** [onSamples] gets each buffer on a dedicated thread; [onError] fires once if the radio stops answering. */
    fun startRx(onSamples: (ByteArray, Int) -> Unit, onError: (String) -> Unit)
    fun close()
}
