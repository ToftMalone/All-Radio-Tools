package com.allnetworktools.util

import androidx.compose.ui.graphics.Color
import com.allnetworktools.data.AppSettings
import com.allnetworktools.data.SignalDisplay
import com.allnetworktools.ui.theme.NetworkColors
import com.allnetworktools.R
import com.allnetworktools.util.S

data class SignalValue(val text: String, val unit: String)

/** Formats a dBm level as dBm or as a percentage of [floor]..[ceiling], per the user setting. */
fun signalValue(dbm: Int?, settings: AppSettings, floor: Float, ceiling: Float): SignalValue = when {
    dbm == null -> SignalValue("—", if (settings.signal == SignalDisplay.Percent) "%" else "dBm")
    settings.signal == SignalDisplay.Percent -> SignalValue(fmt(((dbm - floor) / (ceiling - floor) * 100f).coerceIn(0f, 100f)), "%")
    else -> SignalValue(fmt(dbm), "dBm")
}

enum class Quality(val label: String) { Good(S(R.string.good)), Fair(S(R.string.fair)), Poor(S(R.string.weak)) }

fun quality(value: Float, good: Float, fair: Float) = when {
    value >= good -> Quality.Good
    value >= fair -> Quality.Fair
    else -> Quality.Poor
}

fun NetworkColors.of(q: Quality): Color = when (q) {
    Quality.Good -> good
    Quality.Fair -> fair
    Quality.Poor -> poor
}

fun wifiQualityLabel(rssi: Int): String = when {
    rssi > -55 -> S(R.string.excellent)
    rssi > -67 -> S(R.string.good)
    rssi > -75 -> S(R.string.fair)
    else -> S(R.string.weak)
}
