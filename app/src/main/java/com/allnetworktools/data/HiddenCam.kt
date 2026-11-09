package com.allnetworktools.data

import com.allnetworktools.data.net.LanDevice

enum class CamSource { Wifi, Ble, Lan }

/** Why a device looks like a camera. */
enum class CamReason {
    /** Its address belongs to a camera maker. */
    Maker,
    /** Its name is one cameras use (network set up by the camera, model, app). */
    Name,
    /** A chip found in cheap Wi-Fi cameras (and other connected gadgets). */
    Chip,
    /** It answers on video streaming ports (RTSP, ONVIF, maker protocols). */
    Ports,
    /** Its signal is strong: it is in the room or next to it. */
    Near,
}

data class CamSuspect(
    val key: String,
    val source: CamSource,
    val name: String,
    /** MAC address (Wi-Fi, Bluetooth) or IP address (local network). */
    val address: String,
    val vendor: String?,
    val rssi: Int?,
    val reasons: Set<CamReason>,
    /** A camera maker, a camera name or a video port; otherwise only a chip cameras often use. */
    val likely: Boolean,
    val ports: List<Int> = emptyList(),
)

/**
 * Hidden camera clues in what the radios hear: who made the device (IEEE register), the name it gives itself, and,
 * on the network the phone is connected to, video streaming ports. Clues, not proof: a wired camera, or one that only
 * records to its memory card, sends nothing to hear.
 */
object HiddenCam {
    /** Makers of cameras (security, consumer, the cheap "mini" ones), as they appear in the IEEE register. */
    private val cameraMakers = listOf(
        "hikvision", "dahua", "uniview", "ezviz", "wyze", "reolink", "arlo technology", "immedia", "shanghai xiaoyi",
        "hanwha vision", "samsung techwin", "axis communications", "vivotek", "amcrest", "swann communications", "lorex",
        "dropcam", "canary connect", "gwelltimes", "reecam", "zmodo", "tiandy", "mobotix", "milesight", "avigilon",
        "night owl sp", "bosch security systems", "ring llc", "qihoo", "360 ai technology", "ingenic semiconductor", "anyka",
    )

    /** Chips of most no-name Wi-Fi cameras, but also of plugs, bulbs and sensors. */
    private val cameraChips = listOf("espressif", "beken", "tuya", "hisilicon")

    private val cameraName = Regex(
        "(?i)(^|[^a-z])(ipc|ipcam|ip-?cam|hdwificam|wifi-?cam|webcam|mini[ _-]?cam|spy[ _-]?cam|camera|kamera|camara|cámara|caméra|" +
            "v380|ezviz|hikvision|dahua|imou|reolink|wyze|annke|foscam|esp32-?cam|lookcam|icsee|xmeye|cloudcam|tapo[_ -]?c\\d|" +
            "ring setup|arlo|blink|wansview|eufycam|ipc365|p2pcam|hd[_-]?cam|ycc365|yoosee|camhi|v720|littlelf|mvcam|a9[_-]cam)",
    )
    private val cameraNameStart = Regex("(?i)^(cam[_-]|yi[_-]|mv\\d{4,}|a9[_-]|x5[_-]|hd[_-]?wifi)")
    private val gadgetName = Regex("(?i)^(esp[_-]|esp32|esp8266|smart ?life|tuya|sl-|bk7231|ty|ty[_-].*)$")

    fun isCameraMaker(vendor: String?): Boolean = vendor != null && vendor.lowercase().let { v -> cameraMakers.any { it in v } }
    fun isCameraChip(vendor: String?): Boolean = vendor != null && vendor.lowercase().let { v -> cameraChips.any { it in v } }
    fun isCameraName(name: String?): Boolean = !name.isNullOrBlank() && (cameraName.containsMatchIn(name) || cameraNameStart.containsMatchIn(name))
    fun isGadgetName(name: String?): Boolean = !name.isNullOrBlank() && gadgetName.matches(name.trim())

    /** A device heard over the air, or null when nothing points to a camera. */
    fun assess(source: CamSource, address: String, name: String, vendor: String?, rssi: Int?): CamSuspect? {
        val reasons = buildSet {
            if (isCameraMaker(vendor)) add(CamReason.Maker)
            if (isCameraName(name)) add(CamReason.Name)
            if (isCameraChip(vendor) || isGadgetName(name)) add(CamReason.Chip)
        }
        if (reasons.isEmpty()) return null
        val all = if (rssi != null && rssi >= NEAR_DBM) reasons + CamReason.Near else reasons
        return CamSuspect(source.name + address, source, name, address, vendor, rssi, all, CamReason.Maker in reasons || CamReason.Name in reasons)
    }

    /** Ports cameras stream or are driven on: RTSP, Hikvision, Dahua, XMEye, ONVIF. */
    val cameraPorts = listOf(554, 8554, 8000, 37777, 34567, 8899)
    private val strongPorts = setOf(554, 8554, 37777, 34567)

    /** A device of the local network, with the camera ports it answered on. */
    fun assessLan(d: LanDevice, open: Set<Int>): CamSuspect? {
        if (d.isSelf) return null
        val ports = open.filter { it in cameraPorts }.sorted()
        val names = listOfNotNull(d.mdnsName, d.hostname)
        val reasons = buildSet {
            if (ports.isNotEmpty()) add(CamReason.Ports)
            if (names.any(::isCameraName) || d.services.any { it.startsWith("_rtsp") }) add(CamReason.Name)
        }
        if (reasons.isEmpty()) return null
        // ONVIF or the Hikvision port alone: many routers and NAS answer there too.
        val likely = CamReason.Name in reasons || ports.any { it in strongPorts }
        return CamSuspect("Lan" + d.ip, CamSource.Lan, d.name, d.ip, null, null, reasons, likely, ports)
    }

    /** Most suspicious first: likely ones, then the nearest. */
    fun sort(list: List<CamSuspect>): List<CamSuspect> =
        list.sortedWith(compareByDescending<CamSuspect> { it.likely }.thenByDescending { it.rssi ?: -200 }.thenBy { it.name })

    const val NEAR_DBM = -55
}
