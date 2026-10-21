package com.allnetworktools.data.sdr

import kotlin.math.floor
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Meshtastic modem presets (firmware RadioInterface.cpp): LoRa spreading factor and bandwidth. The coding rate is read
 * from each frame's header, and every preset uses sync word 0x2B. [channelName] is the name the firmware gives a channel
 * left unnamed, such as the public one: it feeds both the channel hash in each packet and the choice of frequency slot.
 */
enum class MeshPreset(val channelName: String, val sf: Int, val bandwidthHz: Int) {
    LongFast("LongFast", 11, 250_000),
    LongModerate("LongMod", 11, 125_000),
    LongSlow("LongSlow", 12, 125_000),
    LongTurbo("LongTurbo", 11, 500_000),
    MediumFast("MediumFast", 9, 250_000),
    MediumSlow("MediumSlow", 10, 250_000),
    ShortFast("ShortFast", 7, 250_000),
    ShortSlow("ShortSlow", 8, 250_000),
    ShortTurbo("ShortTurbo", 7, 500_000);

    val description: String get() = "SF$sf · ${bandwidthHz / 1000} kHz"
}

/** A regulatory region's band as the firmware defines it (RadioInterface.cpp, RDEF). */
enum class MeshRegion(val label: String, val startMhz: Double, val endMhz: Double) {
    Eu868("Europe 868", 869.4, 869.65),
    Eu433("Europe 433", 433.0, 434.0),
    Us(S(R.string.north_america), 902.0, 928.0),
    Anz(S(R.string.australia_nz), 915.0, 928.0),
    Nz865(S(R.string.new_zealand_865), 864.0, 868.0),
    Ru(S(R.string.russia), 868.7, 869.2),
    Ua868("Ukraine 868", 868.0, 868.6),
    Ua433("Ukraine 433", 433.0, 434.7),
    In(S(R.string.india), 865.0, 867.0),
    Jp(S(R.string.japan), 920.5, 923.5),
    Kr(S(R.string.korea), 920.0, 923.0),
    Tw(S(R.string.taiwan), 920.0, 925.0),
    Cn(S(R.string.china), 470.0, 510.0),
    Th(S(R.string.thailand), 920.0, 925.0),
    My919(S(R.string.malaysia_919), 919.0, 924.0),
    Sg923(S(R.string.singapore), 917.0, 925.0),
    Br902(S(R.string.brazil), 902.0, 907.5),
}

/** The channel plan a node derives from its region, preset and primary channel name when no frequency is set by hand. */
object MeshRadio {
    /** The firmware's string hash (djb2) of the channel name, as an unsigned 32-bit value. */
    fun hash(name: String): Long {
        var h = 5381L
        for (b in name.toByteArray(Charsets.UTF_8)) h = ((h shl 5) + h + (b.toLong() and 0xFF)) and 0xFFFFFFFFL
        return h
    }

    /** How many channels of the preset's width fit in the region; 0 when the band is narrower than one channel. */
    fun slots(region: MeshRegion, preset: MeshPreset): Int =
        floor((region.endMhz - region.startMhz) / (preset.bandwidthHz / 1e6) + 1e-9).toInt()

    /** The slot (0-based) a node picks by default: the primary channel name's hash modulo the number of slots. */
    fun defaultSlot(region: MeshRegion, preset: MeshPreset, channelName: String = preset.channelName): Int? {
        val n = slots(region, preset)
        return if (n <= 0) null else (hash(channelName) % n).toInt()
    }

    /** Centre frequency in MHz of [slot] (0-based), or null when it does not fit the region. */
    fun frequencyMhz(region: MeshRegion, preset: MeshPreset, slot: Int): Double? {
        if (slot !in 0 until slots(region, preset)) return null
        val bw = preset.bandwidthHz / 1e6
        return region.startMhz + bw / 2 + slot * bw
    }

    /** Semtech chips turn on the low-data-rate optimisation for symbols of 16 ms or more. */
    fun ldro(preset: MeshPreset): Boolean = LoraPhy.lowDataRate(preset.sf, preset.bandwidthHz.toDouble())
}
