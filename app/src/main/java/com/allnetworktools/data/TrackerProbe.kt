package com.allnetworktools.data

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import com.allnetworktools.R
import com.allnetworktools.util.S

/**
 * What a Find My accessory tells once connected: its name (Generic Access 0x2A00), model (Device Information
 * 0x2A24) and whether it offers the first-generation AirTag service. [error] says why nothing could be read.
 */
data class FindMyProbe(
    val name: String?,
    val model: String?,
    val airTagService: Boolean,
    val services: Int,
    val error: String? = null,
    /** Manufacturer and model from the Find My accessory information service (AirPods, third-party tags…). */
    val findMyMaker: String? = null,
    val findMyModel: String? = null,
)

/** Find My network accessory information service and its manufacturer / model name characteristics. */
private const val FINDMY_INFO_SERVICE = "87290102-"
private const val FINDMY_MAKER = "6aa50002-"
private const val FINDMY_MODEL = "6aa50003-"

/**
 * Connects to a Find My accessory, reads what identifies it and disconnects, as AirGuard does to tell an AirTag from
 * other accessories: AirTags often announce themselves as a plain Find My accessory in their advertisements.
 * Nothing is written to the tag.
 */
suspend fun probeFindMy(context: Context, address: String, timeoutMs: Long = 20_000): FindMyProbe {
    val client = GattClient(context, address)
    return try {
        withTimeoutOrNull(timeoutMs) {
            client.connect(10_000)
            val services = client.discover()
            suspend fun read(service: Int, char: Int): String? =
                services.firstOrNull { GattNames.uuid16(it.uuid) == service }?.getCharacteristic(GattNames.uuid(char))
                    ?.let { c -> runCatching { client.read(c) }.getOrNull() }?.let(BleIdentifier::text)
            val info = services.firstOrNull { it.uuid.toString().lowercase().startsWith(FINDMY_INFO_SERVICE) }
            suspend fun readInfo(prefix: String): String? =
                info?.characteristics?.firstOrNull { it.uuid.toString().lowercase().startsWith(prefix) }
                    ?.let { c -> runCatching { client.read(c) }.getOrNull() }?.let(BleIdentifier::text)
            FindMyProbe(
                read(0x1800, 0x2A00), read(0x180A, 0x2A24), services.any { it.uuid == SoundProtocol.AirTag.service }, services.size,
                findMyMaker = readInfo(FINDMY_MAKER), findMyModel = readInfo(FINDMY_MODEL),
            )
        } ?: FindMyProbe(null, null, false, 0, S(R.string.no_answer_within_s, timeoutMs / 1000))
    } catch (e: GattException) {
        FindMyProbe(null, null, false, 0, e.message ?: S(R.string.gatt_error_2, e.status))
    } catch (e: SecurityException) {
        FindMyProbe(null, null, false, 0, S(R.string.bluetooth_permission_missing))
    } finally {
        client.close()
    }
}
