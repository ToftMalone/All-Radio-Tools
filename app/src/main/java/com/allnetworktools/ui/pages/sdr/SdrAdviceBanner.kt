package com.allnetworktools.ui.pages.sdr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.data.sdr.HealthJudge
import com.allnetworktools.data.sdr.SdrAdvice
import com.allnetworktools.data.sdr.SdrHealth
import com.allnetworktools.model.Tool
import com.allnetworktools.ui.components.Advice
import com.allnetworktools.ui.components.AdviceCard
import com.allnetworktools.ui.theme.AntTheme
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.util.S
import kotlinx.coroutines.delay

/** For screenshots: the advice shown whatever the radio says. */
internal object SdrAdviceForTest { var forced: SdrAdvice? = null }

/**
 * The reception advice on top of an SDR tool's page, while that tool holds the radio: [SdrHealth] read once a second and
 * judged by [HealthJudge].
 */
@Composable
fun SdrAdviceBanner(tool: Tool) {
    var advice by remember(tool) { mutableStateOf(SdrAdviceForTest.forced) }
    LaunchedEffect(tool) {
        if (SdrAdviceForTest.forced != null) return@LaunchedEffect
        val judge = HealthJudge(adsb = tool == Tool.Adsb)
        while (true) {
            advice = if (SdrHealth.owner == tool.name) judge.judge(SdrHealth.snapshot()) else { judge.judge(SdrHealth.snapshot().copy(rxSinceNs = 0L)); null }
            delay(1_000)
        }
    }
    AdviceCard(advice?.let { sdrAdvice(it) })
}

@Composable
private fun sdrAdvice(a: SdrAdvice): Advice {
    val net = AntTheme.net
    return when (a) {
        SdrAdvice.SamplesLostUsb -> Advice("sdr-usb", Sym.Usb, S(R.string.advice_usb_title), S(R.string.advice_usb_body), net.poor)
        SdrAdvice.SamplesLostPhone -> Advice("sdr-phone", Sym.Memory, S(R.string.advice_phone_title), S(R.string.advice_phone_body), net.poor)
        SdrAdvice.NothingPasses -> Advice("sdr-nothing", Sym.Antenna, S(R.string.advice_nothing_title), S(R.string.advice_nothing_body), net.fair)
        SdrAdvice.Damaged -> Advice("sdr-damaged", Sym.Antenna, S(R.string.advice_damaged_title), S(R.string.advice_damaged_body), net.fair)
        SdrAdvice.NoAircraft -> Advice("sdr-adsb", Sym.Flight, S(R.string.advice_adsb_title), S(R.string.advice_adsb_body), net.fair)
    }
}
