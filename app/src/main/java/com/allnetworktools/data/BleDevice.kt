package com.allnetworktools.data

import android.content.Context
import com.allnetworktools.ui.theme.Sym
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class BleVendor(val label: String) {
    Apple("Apple"), Microsoft("Microsoft"), Samsung("Samsung"), Google("Google"), Mesh("Bluetooth Mesh"), Beacon("Tag"),
}

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val txPower: Int?,
    val kind: BleKind,
    val maker: String?,
    val connectable: Boolean,
    val lastSeen: Long,
    /** 16-bit (or full) service UUIDs from the advertisement. */
    val services: List<String> = emptyList(),
    val manufacturerHex: String? = null,
    val flags: Int? = null,
    /** Interval announced by the device, else the mean gap between the advertisements we received. */
    val intervalMs: Int? = null,
    val companyId: Int? = null,
    /** Raw advertising + scan response payload, uppercase hex without separators. */
    val raw: String? = null,
    /** What the identification engine thinks the device is (product or category). */
    val model: String? = null,
    val confidence: Int = 0,
    val evidence: List<String> = emptyList(),
    val details: List<Pair<String, String>> = emptyList(),
    val identIcon: String? = null,
    /** Received through a BLE 5 extended advertisement. */
    val extended: Boolean = false,
    val ads: List<AdStructure> = emptyList(),
    /** The identity comes from a connection (Generic Access / Device Information). */
    val fromGatt: Boolean = false,
) {
    /** AD structure types present in the payload (0x01 flags, 0xFF manufacturer data, 0x2A mesh…). */
    val adTypes: Set<Int> get() = ads.map { it.type }.toSet()

    val isUnknown get() = kind == BleKind.Unknown

    /** Below 60 % the classification is a probable guess. */
    val guessed get() = kind != BleKind.Unknown && confidence < 60

    val vendors: Set<BleVendor>
        get() = buildSet {
            val uuids = Ad.uuids16(ads)
            val makers = Ad.manufacturer(ads).keys + listOfNotNull(companyId)
            if (0x004C in makers || maker == "Apple") add(BleVendor.Apple)
            if (0x0006 in makers || maker == "Microsoft") add(BleVendor.Microsoft)
            if (0x0075 in makers || maker == "Samsung" || 0xFD5A in uuids || 0xFD69 in uuids) add(BleVendor.Samsung)
            if (0x00E0 in makers || maker == "Google" || uuids.any { it in setOf(0xFE2C, 0xFEF3, 0xFE9F) }) add(BleVendor.Google)
            if (0x1827 in uuids || 0x1828 in uuids || adTypes.any { it in 0x29..0x2B }) add(BleVendor.Mesh)
            if (kind == BleKind.Tag || 0xFEAA in uuids) add(BleVendor.Beacon)
        }

    /** Best available name: the advertised one, else what we identified, else the maker. */
    val title: String
        get() = name ?: model?.takeIf { confidence >= 50 } ?: maker?.let { S(R.string.device, it) } ?: S(R.string.unknown_device)

    val displayName: String get() = title

    /**
     * Distance estimated from the signal, log-distance path loss with exponent 2.2 (indoors, line of sight): the
     * power at 1 m is the advertised transmit power (measured at 0 m) minus 41 dB, or −59 dBm, the usual value for
     * phones and tags. Bodies and walls make it read farther; take it as an order of magnitude.
     */
    val distanceM: Double get() {
        val p1m = txPower?.let { it - 41 } ?: -59
        return Math.pow(10.0, (p1m - rssi) / 22.0).coerceIn(0.1, 100.0)
    }

    /** Stable pseudo-angle so a device keeps its place on the radar. */
    val angle: Float get() = (abs(address.hashCode()) % 360).toFloat()

    val icon: String get() = identIcon ?: kind.icon

    /** What can be said about the address: private addresses change every ~15 minutes. */
    val addressNote: String
        get() = when ((address.take(2).toIntOrNull(16) ?: 0) shr 6) {
            3 -> S(R.string.static_random_fixed_until_the_device)
            1 -> S(R.string.resolvable_private_changes_every_15_min)
            2 -> S(R.string.public_text)
            else -> S(R.string.public_or_non_resolvable_private)
        }

    companion object {
        /** Builds a device from the merged advertisement structures, running the identification engine. */
        fun from(
            address: String,
            name: String?,
            rssi: Int,
            txPower: Int?,
            connectable: Boolean,
            lastSeen: Long,
            ads: List<AdStructure>,
            gatt: GattIdentity? = null,
            extended: Boolean = false,
            measuredIntervalMs: Int? = null,
            identity: BleIdentity? = null,
        ): BleDevice {
            val id = identity ?: BleIdentify.identify(name, ads, address, gatt)
            val mfg = ads.firstOrNull { it.type == Ad.MANUFACTURER && it.data.size >= 2 }
            val services = Ad.uuids16(ads).sorted().map { "0x%04X".format(it) } + Ad.uuids128(ads).sorted()
            return BleDevice(
                address = address,
                name = name ?: Ad.name(ads) ?: gatt?.name,
                rssi = rssi,
                txPower = txPower ?: Ad.txPower(ads),
                kind = id.kind,
                maker = id.maker,
                connectable = connectable,
                lastSeen = lastSeen,
                services = services,
                manufacturerHex = mfg?.let { "%04X ".format(it.data.u16le(0)) + it.data.hex(2) },
                flags = Ad.flags(ads),
                intervalMs = Ad.advertisedIntervalMs(ads) ?: measuredIntervalMs,
                companyId = mfg?.data?.u16le(0),
                raw = Ad.hex(ads).ifEmpty { null },
                model = id.model,
                confidence = id.confidence,
                evidence = id.evidence,
                details = id.details,
                identIcon = id.icon,
                extended = extended,
                ads = ads,
                fromGatt = gatt != null && id.known,
            )
        }
    }
}

/** Identities learned by connecting to devices, kept in memory for the session only: nothing is written to disk. */
class BleIdentityStore(context: Context?) {
    private val map = ConcurrentHashMap<String, GattIdentity>()
    private val _version = MutableStateFlow(0)

    /** Bumped on every change so scanners can re-run the identification. */
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        // Identities saved by earlier versions.
        context?.let { runCatching { File(it.filesDir, "ble_identities.json").delete() } }
    }

    fun get(address: String): GattIdentity? = map[address]

    val size get() = map.size

    fun put(address: String, id: GattIdentity) {
        map[address] = id
        _version.value++
    }

    fun clear() {
        map.clear()
        _version.value++
    }
}
