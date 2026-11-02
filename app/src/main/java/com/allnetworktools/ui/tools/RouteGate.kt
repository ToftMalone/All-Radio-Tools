package com.allnetworktools.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.allnetworktools.R
import com.allnetworktools.data.net.RouteSource
import com.allnetworktools.data.net.Transport
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.util.S

/**
 * Which network an Internet tool is tied to, and whether it was there the last time the tool started. A Wi-Fi tool
 * works over Wi-Fi only, a mobile data tool over mobile data only, whatever the phone browses with.
 */
class RouteGate(val source: RouteSource = RouteSource.System, val transport: Transport? = null) {
    /** The network was not there when the tool tried to start. */
    var missing by mutableStateOf(false)

    /** Runs [block] on the network; if it is not there, notes it and returns false. */
    suspend fun run(block: suspend (com.allnetworktools.data.net.NetRoute) -> Unit): Boolean {
        missing = false
        val ran = source.use { block(it) }
        if (ran == null) missing = true
        return ran != null
    }
}

/** Shown in place of a result when the tool's network is missing. */
@Composable
fun RouteError(gate: RouteGate, retry: () -> Unit) {
    val wifi = gate.transport == Transport.Wifi
    ToolError(
        if (wifi) Sym.WifiOff else Sym.CellOff,
        S(if (wifi) R.string.route_wifi_missing_title else R.string.route_mobile_missing_title),
        S(if (wifi) R.string.route_wifi_missing_text else R.string.route_mobile_missing_text),
        S(R.string.retry), retry,
    )
}
