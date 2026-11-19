package com.allnetworktools.data.sdr

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TP-Link Tapo sub-GHz sensors (T100, T110, T310, S200…, through an H100 / H200 hub): a proprietary GFSK link at
 * 50 kbit/s on 863.35, 864.35 and 868.35 MHz in Europe (the S200B's EU test report) and 920.9 to 922.3 MHz in the US
 * (FCC grants). Its frame format is not public, so a Tapo is recognised by its signature, not decoded: an FSK burst on
 * one of those channels, with an alternating preamble and every element a whole number of 20 µs bits.
 */
object Tapo {
    val CHANNELS_HZ = listOf(863_350_000.0, 864_350_000.0, 868_350_000.0, 920_900_000.0, 921_700_000.0, 922_300_000.0)
    private const val BIT_US = 20.0

    fun match(t: IsmTrain, hz: Double): IsmReading? {
        if (!t.fsk || t.p.size < 8) return null
        val ch = CHANNELS_HZ.indexOfFirst { abs(it - hz) <= 30_000 }
        if (ch < 0) return null
        val d = ArrayList<Float>(2 * t.p.size)
        for (q in t.p) { d += q.pulseUs; d += q.gapUs }
        d.removeAt(d.lastIndex)
        // The preamble: single bits, after a first element the squelch may have cut.
        var pre = 1
        while (pre < d.size && d[pre] in 14f..26f) pre++
        if (pre - 1 < 12) return null
        val unit = d.subList(1, pre).average()
        if (unit !in BIT_US * 0.94..BIT_US * 1.06) return null
        var bits = 0
        var fit = 0
        for (x in d) {
            val k = (x / unit).roundToInt()
            if (k >= 1 && abs(x - k * unit) <= 0.3 * unit) fit++
            bits += maxOf(1, k)
        }
        if (fit < d.size * 0.9 || bits < 80) return null
        return IsmReading(
            model = "TP-Link Tapo", kind = "Sensor", id = "—", mic = "signature", channel = ch + 1,
            channelName = String.format(Locale.getDefault(), "%.2f MHz", CHANNELS_HZ[ch] / 1e6),
            fields = listOf(
                IsmField("rate", "", 0.0, 0, String.format(Locale.getDefault(), "GFSK %.0f kbit/s", 1000 / unit)),
                IsmField("bits", "", 0.0, 0, "$bits bits"),
            ),
        )
    }
}
