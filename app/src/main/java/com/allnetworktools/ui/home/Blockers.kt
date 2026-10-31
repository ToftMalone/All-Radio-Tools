package com.allnetworktools.ui.home

import com.allnetworktools.Blocker
import com.allnetworktools.model.Network
import com.allnetworktools.ui.theme.Sym
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Short copy for a disabled home card. */
data class CardBlock(val icon: String, val title: String, val message: String, val button: String?, val buttonIcon: String)

/** Full-page StatePanel copy. */
data class PageBlock(val icon: String, val title: String, val message: String, val primary: String?, val primaryIcon: String, val secondary: String?)

fun cardBlock(b: Blocker): CardBlock = when (b) {
    Blocker.WifiOff -> CardBlock(Sym.WifiOff, S(R.string.wi_fi_off), S(R.string.turn_it_on_to_see_the), S(R.string.turn_on), Sym.PowerSettings)
    Blocker.BluetoothOff -> CardBlock(Sym.BluetoothDisabled, S(R.string.bluetooth_off), S(R.string.needed_to_scan_devices), S(R.string.turn_on), Sym.PowerSettings)
    Blocker.NearbyPermission -> CardBlock(Sym.BluetoothDisabled, S(R.string.permission_needed), S(R.string.allow_nearby_devices), S(R.string.allow), Sym.BluetoothSearching)
    Blocker.Airplane -> CardBlock(Sym.Airplane, S(R.string.airplane_mode), S(R.string.no_radio_measurement_possible), S(R.string.settings), Sym.OpenInNew)
    Blocker.PhonePermission -> CardBlock(Sym.SimCard, S(R.string.permission_needed), S(R.string.allow_phone_access), S(R.string.allow), Sym.SimCard)
    Blocker.NoSim -> CardBlock(Sym.NoSim, S(R.string.no_sim), S(R.string.insert_a_sim_or_turn_on), S(R.string.settings), Sym.OpenInNew)
    Blocker.LocationPermission -> CardBlock(Sym.LocationOff, S(R.string.location_needed), S(R.string.allow_precise_location_to_read_the), S(R.string.allow), Sym.MyLocation)
    Blocker.LocationOff -> CardBlock(Sym.LocationOff, S(R.string.location_off), S(R.string.turn_it_on_to_receive_the), S(R.string.turn_on), Sym.PowerSettings)
    Blocker.NoHardware -> CardBlock(Sym.Block, S(R.string.not_available), S(R.string.this_device_does_not_have_this), null, Sym.Block)
    Blocker.SdrMissing -> CardBlock(Sym.Usb, S(R.string.no_hackrf), S(R.string.plug_in_a_hackrf_over_usb), null, Sym.Usb)
    Blocker.NfcOff -> CardBlock(Sym.Nfc, S(R.string.nfc_off), S(R.string.nfc_off_card), S(R.string.turn_on), Sym.PowerSettings)
}

fun pageBlock(network: Network, b: Blocker, permanentlyDenied: Boolean): PageBlock = when (b) {
    Blocker.WifiOff -> PageBlock(Sym.WifiOff, S(R.string.wi_fi_off), S(R.string.turn_on_wi_fi_to_show), S(R.string.turn_on_wi_fi), Sym.PowerSettings, S(R.string.wi_fi_settings))
    Blocker.BluetoothOff -> PageBlock(Sym.BluetoothDisabled, S(R.string.bluetooth_off), S(R.string.turn_on_bluetooth_to_see_your), S(R.string.turn_on_bluetooth), Sym.PowerSettings, S(R.string.bluetooth_settings))
    Blocker.NearbyPermission -> PageBlock(
        Sym.BluetoothDisabled, S(R.string.nearby_devices_access_needed),
        S(R.string.the_nearby_devices_permission_is_needed),
        if (permanentlyDenied) S(R.string.open_settings) else S(R.string.allow), if (permanentlyDenied) Sym.OpenInNew else Sym.BluetoothSearching, null,
    )
    Blocker.Airplane -> PageBlock(Sym.Airplane, S(R.string.airplane_mode_on), S(R.string.the_modem_is_off_turn_airplane), S(R.string.turn_off_airplane_mode), Sym.AirplaneOff, null)
    Blocker.PhonePermission -> PageBlock(
        Sym.SimCard, S(R.string.phone_access_needed),
        S(R.string.the_phone_permission_lets_the_app),
        if (permanentlyDenied) S(R.string.open_settings) else S(R.string.allow), if (permanentlyDenied) Sym.OpenInNew else Sym.SimCard, null,
    )
    Blocker.NoSim -> PageBlock(Sym.NoSim, S(R.string.no_sim_card), S(R.string.insert_a_sim_card_or_turn), S(R.string.network_settings), Sym.OpenInNew, null)
    Blocker.LocationPermission -> PageBlock(
        Sym.LocationOff, S(R.string.location_access_needed),
        S(R.string.precise_location_is_needed_to_read),
        if (permanentlyDenied) S(R.string.open_settings) else S(R.string.allow), if (permanentlyDenied) Sym.OpenInNew else Sym.MyLocation,
        if (permanentlyDenied) null else S(R.string.open_settings),
    )
    Blocker.LocationOff -> PageBlock(Sym.LocationOff, S(R.string.location_off), S(R.string.turn_on_the_device_s_location), S(R.string.turn_on_location), Sym.PowerSettings, null)
    Blocker.NoHardware -> PageBlock(Sym.Block, S(R.string.unavailable_2, network.label), S(R.string.this_device_does_not_have_the), null, Sym.Block, null)
    Blocker.NfcOff -> PageBlock(Sym.Nfc, S(R.string.nfc_off), S(R.string.nfc_off_page), S(R.string.nfc_turn_on), Sym.PowerSettings, null)
    Blocker.SdrMissing -> PageBlock(
        Sym.Usb, S(R.string.plug_in_a_hackrf),
        S(R.string.connect_a_hackrf_one_or_rad1o) +
            S(R.string.the_app_detects_it_as_soon),
        null, Sym.Usb, null,
    )
}
