package com.allnetworktools.util

import kotlin.math.abs
import kotlin.math.roundToLong
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * Numbers the way the phone's language writes them, with a true minus sign: "1 234,5" in French (narrow no-break
 * space, decimal comma), "1,234.5" in English.
 */
fun fmt(value: Number, decimals: Int = 0): String {
    val v = value.toDouble()
    if (v.isNaN()) return "—"
    val a = abs(v)
    val fixed = if (decimals == 0) a.roundToLong().toString() else String.format(java.util.Locale.ROOT, "%.${decimals}f", a)
    val french = java.util.Locale.getDefault().language == "fr"
    val symbols = java.text.DecimalFormatSymbols.getInstance()
    val group = if (french) "\u202F" else symbols.groupingSeparator.toString()
    val decimal = if (french) "," else symbols.decimalSeparator.toString()
    val parts = fixed.split('.')
    val int = parts[0].reversed().chunked(3).joinToString(group).reversed()
    val out = if (parts.size > 1) "$int$decimal${parts[1]}" else int
    val isZero = out.none { it in '1'..'9' }
    return if (v < 0 && !isZero) "\u2212$out" else out
}

fun plural(n: Int, singular: String, plural: String = singular + "s") = if (n > 1) plural else singular

fun durationFr(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 1 -> S(R.string.less_than_a_minute)
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60} min"
    }
}
