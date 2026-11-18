package com.allnetworktools.data

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** How busy a channel is with the networks heard around. */
enum class ChannelLoad { Free, Calm, Busy, Saturated }

/** A channel the box could use: where it sits, how loaded it is, and how many other networks share it. */
data class ChannelChoice(
    /** Primary channel number (the first one of a wide block). */
    val channel: Int,
    /** Last channel of the block for a 40/80/160 MHz block, else the same as [channel]. */
    val lastChannel: Int,
    val widthMhz: Int,
    /** Interference felt on it: the power of the networks overlapping it, weighted by how much they overlap, dBm. */
    val loadDbm: Double,
    val networks: Int,
    /** Radar detection channel (5 GHz, 52 to 144): the box must leave it if a radar appears. */
    val dfs: Boolean,
) {
    val load: ChannelLoad get() = when {
        loadDbm < -85 -> ChannelLoad.Free
        loadDbm < -75 -> ChannelLoad.Calm
        loadDbm < -65 -> ChannelLoad.Busy
        else -> ChannelLoad.Saturated
    }
}

/** The advice for one band: the channels ranked, and the channel the user's network is on now. */
data class ChannelAdvice(val ranked: List<ChannelChoice>, val current: ChannelChoice?) {
    val best: ChannelChoice? get() = ranked.firstOrNull()

    /** True when moving is worth it: the best channel is clearly quieter (by [MARGIN_DB]) than the current one. */
    val worthMoving: Boolean get() {
        val b = best ?: return false
        val c = current ?: return false
        return b.channel != c.channel && c.loadDbm - b.loadDbm >= MARGIN_DB
    }

    companion object { const val MARGIN_DB = 3.0 }
}

/**
 * Which channel to set the box on, from the networks the scan heard. Each network spreads its power over the band it
 * occupies (centre and width from the scan); a candidate channel collects the power of every network overlapping it,
 * weighted by the overlap. Networks on the very same channel share the air politely (they wait for each other); a
 * network only partly overlapping is worse, since neither side decodes the other and both just see noise, so partial
 * overlap counts [PARTIAL_PENALTY] times more. The user's own access points (same SSID) do not count against it.
 */
object ChannelAdvisor {
    const val PARTIAL_PENALTY = 1.5

    /** 2.4 GHz: only the three channels that do not overlap each other are worth recommending. */
    private val Channels24 = listOf(1, 6, 11)

    /** 5 GHz channel blocks by width (20 MHz channels from 36 to 165). */
    private val Blocks5: Map<Int, List<IntRange>> = mapOf(
        20 to (listOf(36, 40, 44, 48, 52, 56, 60, 64) + (100..144 step 4) + listOf(149, 153, 157, 161, 165)).map { it..it },
        40 to listOf(36..40, 44..48, 52..56, 60..64, 100..104, 108..112, 116..120, 124..128, 132..136, 140..144, 149..153, 157..161),
        80 to listOf(36..48, 52..64, 100..112, 116..128, 132..144, 149..161),
        160 to listOf(36..64, 100..128),
    )

    private fun mhz5(ch: Int) = 5000 + 5 * ch
    private fun mhz24(ch: Int) = if (ch == 14) 2484 else 2407 + 5 * ch

    private fun mw(dbm: Int) = 10.0.pow(dbm / 10.0)

    /**
     * The advice for [band]: [aps] is the scan, [ownSsid] the network the phone is connected to (its access points are
     * left out), [ownBssid] its access point, which gives the current channel and width.
     */
    fun advise(aps: List<WifiAp>, band: WifiBand, ownSsid: String?, ownBssid: String?): ChannelAdvice {
        if (band == WifiBand.B6) return ChannelAdvice(emptyList(), null)
        val own = aps.firstOrNull { it.bssid.equals(ownBssid, true) }?.takeIf { it.band == band }
        val ssid = own?.ssid ?: ownSsid?.removeSurrounding("\"")
        val others = aps.filter { it.band == band && !(ssid != null && ssid.isNotEmpty() && it.ssid == ssid) }
        val width = when (band) {
            WifiBand.B24 -> 20
            else -> own?.widthMhz?.takeIf { it in Blocks5 } ?: 80
        }
        val blocks: List<IntRange> = if (band == WifiBand.B24) Channels24.map { it..it } else Blocks5.getValue(width)
        val choices = blocks.map { b -> score(b, band, width, others) }
        val ranked = choices.sortedWith(compareBy<ChannelChoice> { it.loadDbm }.thenBy { if (it.dfs) 1 else 0 }.thenBy { it.channel })
        val current = own?.let { ap ->
            val ch = ap.channel
            val block = blocks.firstOrNull { ch in it } ?: (ch..ch)
            score(block, band, if (band == WifiBand.B24) 20 else ap.widthMhz, others)
        }
        return ChannelAdvice(ranked, current)
    }

    private fun score(block: IntRange, band: WifiBand, width: Int, others: List<WifiAp>): ChannelChoice {
        val (lo, hi) = if (band == WifiBand.B24) {
            val c = mhz24(block.first); (c - 10.0) to (c + 10.0)
        } else (mhz5(block.first) - 10.0) to (mhz5(block.last) + 10.0)
        var power = 0.0
        var count = 0
        for (ap in others) {
            val centre = (ap.centerFrequency.takeIf { it > 0 } ?: ap.frequency).toDouble()
            val w = (if (ap.widthMhz > 0) ap.widthMhz else 20).toDouble()
            val aLo = centre - w / 2
            val aHi = centre + w / 2
            val overlap = min(hi, aHi) - max(lo, aLo)
            if (overlap <= 0) continue
            val fraction = overlap / w
            // Its primary channel inside the block: it shares the air politely; otherwise it is plain interference.
            val polite = ap.channel in block
            power += mw(ap.rssi) * fraction * if (polite) 1.0 else PARTIAL_PENALTY
            count++
        }
        val dfs = band == WifiBand.B5 && block.any { it in 52..144 }
        // An empty channel is as quiet as the noise floor.
        val load = 10 * log10(power + mw(-95))
        return ChannelChoice(block.first, block.last, width, load, count, dfs)
    }
}
