package com.allnetworktools.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allnetworktools.MainViewModel
import com.allnetworktools.R
import com.allnetworktools.model.Tool
import com.allnetworktools.util.S

/** Why an Internet tool cannot be used right now. */
class Unavailable(val title: String, val message: String)

/**
 * The Internet tools of the Wi-Fi network work over Wi-Fi only; their copies in the mobile network work over mobile
 * data only, and not at all while Wi-Fi is connected. Elsewhere the tool is simply unavailable, and says why.
 */
class InternetAvailability(private val onWifi: Boolean, private val onMobileData: Boolean) {
    fun why(tool: Tool): Unavailable? = when (tool) {
        in WifiInternet -> if (onWifi) null else Unavailable(S(R.string.route_wifi_missing_title), S(R.string.route_wifi_missing_text))
        in MobileInternet -> when {
            onWifi -> Unavailable(S(R.string.route_wifi_on_title), S(R.string.route_wifi_on_text))
            !onMobileData -> Unavailable(S(R.string.route_mobile_missing_title), S(R.string.route_mobile_missing_text))
            else -> null
        }
        else -> null
    }

    companion object {
        val WifiInternet = setOf(Tool.Ping, Tool.Trace, Tool.Dns, Tool.Speed, Tool.Ports, Tool.Whois)
        val MobileInternet = setOf(Tool.CellSpeed, Tool.CellPing, Tool.CellTrace, Tool.CellDns, Tool.CellWhois)
    }
}

@Composable
fun rememberInternetAvailability(vm: MainViewModel): InternetAvailability {
    val wifi by vm.onWifi.collectAsStateWithLifecycle()
    val mobile by vm.onMobileData.collectAsStateWithLifecycle()
    return InternetAvailability(wifi, mobile)
}
