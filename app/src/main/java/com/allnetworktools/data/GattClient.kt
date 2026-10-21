package com.allnetworktools.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import com.allnetworktools.R
import com.allnetworktools.util.S

/** A GATT failure with the stack's status code (133 = GATT_ERROR, 8 = supervision timeout…). */
class GattException(val status: Int, message: String = GattNames.status(status)) : Exception(message)

sealed interface GattEvent {
    data class Disconnected(val status: Int) : GattEvent
    data class Changed(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : GattEvent
}

/**
 * Coroutine wrapper over [BluetoothGatt]. The Android stack runs one operation at a time, so every
 * call takes [mutex] and waits for its callback before the next one starts.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, val address: String) {
    private var gatt: BluetoothGatt? = null
    private val mutex = Mutex()
    private var connecting: CompletableDeferred<Unit>? = null
    private var pending: CompletableDeferred<Any?>? = null
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GattEvent> = _events

    var mtu = 23
        private set
    var phy: Int? = null
        private set

    private fun complete(status: Int, value: Any?) {
        val p = pending ?: return
        pending = null
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value) else p.completeExceptionally(GattException(status))
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connecting?.complete(Unit); connecting = null
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val code = if (status == BluetoothGatt.GATT_SUCCESS) 19 else status
                connecting?.completeExceptionally(GattException(code)); connecting = null
                pending?.completeExceptionally(GattException(code)); pending = null
                _events.tryEmit(GattEvent.Disconnected(code))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattClient.mtu = mtu
            complete(BluetoothGatt.GATT_SUCCESS, mtu)
        }

        override fun onPhyRead(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) phy = txPhy
            complete(BluetoothGatt.GATT_SUCCESS, txPhy)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status, g.services)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = complete(status, value)

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) complete(status, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status, null)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status, null)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _events.tryEmit(GattEvent.Changed(c, value))
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) _events.tryEmit(GattEvent.Changed(c, c.value ?: ByteArray(0)))
        }
    }

    suspend fun connect(timeoutMs: Long = 15_000) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw GattException(-1, S(R.string.bluetooth_unavailable))
        val done = CompletableDeferred<Unit>()
        connecting = done
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: throw GattException(133)
        try {
            withTimeout(timeoutMs) { done.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            close(); throw GattException(-2, S(R.string.the_device_did_not_answer_s, timeoutMs / 1000))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> op(timeoutMs: Long = 8_000, start: (BluetoothGatt) -> Boolean): T = mutex.withLock {
        val g = gatt ?: throw GattException(19)
        val d = CompletableDeferred<Any?>()
        pending = d
        if (!start(g)) {
            pending = null
            throw GattException(-3, S(R.string.operation_refused_by_the_bluetooth_stack))
        }
        try {
            withTimeout(timeoutMs) { d.await() } as T
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending = null
            throw GattException(-2, S(R.string.no_answer_from_the_device))
        }
    }

    suspend fun requestMtu(size: Int = 517): Int = runCatching { op<Int> { it.requestMtu(size) } }.getOrDefault(mtu)

    suspend fun readPhy(): Int? = runCatching { op<Int>(3_000) { it.readPhy(); true } }.getOrNull()

    suspend fun discover(): List<BluetoothGattService> = op(20_000) { it.discoverServices() }

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray = op { it.readCharacteristic(c) }

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray) {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Any?> { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Subscribes through the CCCD (0x2902); indications are used when notifications are not offered. */
    suspend fun setNotify(c: BluetoothGattCharacteristic, enable: Boolean) {
        val g = gatt ?: throw GattException(19)
        if (!g.setCharacteristicNotification(c, enable)) throw GattException(-3, S(R.string.subscription_refused))
        val cccd = c.getDescriptor(GattNames.CCCD) ?: return
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        op<Any?> { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
    }

    fun close() {
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        connecting?.cancel(); connecting = null
        pending?.cancel(); pending = null
    }
}

/** Names, icons and decoders for the Bluetooth SIG assigned numbers most often met in the wild. */
object GattNames {
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun uuid16(u: UUID): Int? {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8).toInt(16) else null
    }

    fun uuid(n: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(n))

    fun short(u: UUID): String = uuid16(u)?.let { "0x%04X".format(it) } ?: u.toString().substring(0, 8).uppercase() + "…"

    private val services = mapOf(
        0x1800 to S(R.string.generic_access), 0x1801 to S(R.string.generic_attribute), 0x1802 to S(R.string.immediate_alert), 0x1803 to S(R.string.link_loss),
        0x1804 to S(R.string.tx_power_2), 0x1805 to S(R.string.current_time), 0x180A to S(R.string.device_information), 0x180D to S(R.string.heart_rate),
        0x180F to S(R.string.battery), 0x1810 to S(R.string.blood_pressure), 0x1812 to S(R.string.hid_device), 0x1814 to S(R.string.running),
        0x1816 to S(R.string.cycling_speed_cadence), 0x1818 to S(R.string.cycling_power), 0x181A to S(R.string.environmental_sensing), 0x181C to S(R.string.user_data),
        0x181D to S(R.string.weight_scale), 0x1822 to S(R.string.pulse_oximeter), 0x1826 to S(R.string.fitness_machine), 0x183B to S(R.string.binary_mesh),
        0x184E to "Audio Stream Control", 0x1850 to S(R.string.published_audio), 0xFE2C to "Google Fast Pair", 0xFEAA to "Eddystone",
        0xFD6F to S(R.string.exposure_notification), 0xFE59 to "Nordic DFU", 0xFEE0 to "Mi Band",
    )

    private val characteristics = mapOf(
        0x2A00 to S(R.string.device_name), 0x2A01 to S(R.string.appearance), 0x2A04 to S(R.string.preferred_connection_parameters), 0x2A05 to S(R.string.service_changed),
        0x2A06 to S(R.string.alert_level), 0x2A07 to S(R.string.tx_power_2), 0x2A19 to S(R.string.battery_level), 0x2A23 to S(R.string.system_id),
        0x2A24 to S(R.string.model), 0x2A25 to S(R.string.serial_number), 0x2A26 to S(R.string.firmware_revision), 0x2A27 to S(R.string.hardware_revision),
        0x2A28 to S(R.string.software_revision), 0x2A29 to S(R.string.manufacturer), 0x2A2B to S(R.string.current_time), 0x2A37 to S(R.string.heart_rate_measurement),
        0x2A38 to S(R.string.sensor_location), 0x2A39 to S(R.string.hr_control_point), 0x2A4D to S(R.string.hid_report), 0x2A50 to S(R.string.pnp_id),
        0x2A53 to S(R.string.running_measurement), 0x2A5B to S(R.string.speed_cadence_measurement), 0x2A6D to S(R.string.pressure), 0x2A6E to S(R.string.temperature),
        0x2A6F to S(R.string.humidity), 0x2AA6 to S(R.string.central_address_resolution), 0x2B29 to S(R.string.client_supported_features), 0x2B2A to S(R.string.database_hash),
        0x2B3A to S(R.string.server_supported_features),
    )

    fun serviceName(u: UUID): String = uuid16(u)?.let { services[it] } ?: S(R.string.proprietary_service)

    fun charName(u: UUID): String = uuid16(u)?.let { characteristics[it] } ?: S(R.string.proprietary_characteristic)

    fun serviceIcon(u: UUID): String = when (uuid16(u)) {
        0x180F -> com.allnetworktools.ui.theme.Sym.Battery
        0x180D, 0x1810, 0x1822 -> com.allnetworktools.ui.theme.Sym.Favorite
        0x180A -> com.allnetworktools.ui.theme.Sym.Info
        0x1800, 0x1801 -> com.allnetworktools.ui.theme.Sym.Badge
        0x1802, 0x1803 -> com.allnetworktools.ui.theme.Sym.VolumeUp
        0x181A -> com.allnetworktools.ui.theme.Sym.Thermostat
        0x1812 -> com.allnetworktools.ui.theme.Sym.Keyboard
        else -> com.allnetworktools.ui.theme.Sym.AccountTree
    }

    fun hex(b: ByteArray): String = if (b.isEmpty()) S(R.string.empty) else b.joinToString(" ") { "%02X".format(it) }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** Human reading of a value, or null when the format is unknown. */
    fun decode(u: UUID, b: ByteArray): String? {
        if (b.isEmpty()) return null
        return runCatching {
            when (uuid16(u)) {
                0x2A19 -> "${b[0].toInt() and 0xFF} %"
                0x2A37 -> {
                    val wide = b[0].toInt() and 1 != 0
                    "${if (wide) u16(b, 1) else b[1].toInt() and 0xFF} bpm"
                }
                0x2A38 -> listOf(S(R.string.other), S(R.string.chest), S(R.string.wrist), S(R.string.finger), S(R.string.hand), S(R.string.ear_lobe), S(R.string.foot)).getOrElse(b[0].toInt()) { S(R.string.reserved) }
                0x2A06 -> listOf(S(R.string.no_alert), S(R.string.mild_alert), S(R.string.high_alert)).getOrElse(b[0].toInt()) { S(R.string.reserved) }
                0x2A07 -> "${b[0]} dBm"
                0x2A01 -> S(R.string.appearance_0x).format(u16(b, 0))
                0x2A6E -> "%.2f °C".format(java.util.Locale.getDefault(), (u16(b, 0).toShort()) / 100f)
                0x2A6F -> "%.2f %%".format(java.util.Locale.getDefault(), u16(b, 0) / 100f)
                0x2A04 -> S(R.string.interval_ms_latency, u16(b, 0) * 1.25f, u16(b, 2) * 1.25f, u16(b, 4))
                0x2A50 -> S(R.string.vendor_0x_product_0x_v).format(u16(b, 1), u16(b, 3), u16(b, 5))
                else -> null
            } ?: text(b)
        }.getOrNull()
    }

    private fun text(b: ByteArray): String? {
        val trimmed = b.dropLastWhile { it == 0.toByte() }.toByteArray()
        if (trimmed.isEmpty() || trimmed.size < 2) return null
        val s = String(trimmed, Charsets.UTF_8)
        return if (s.all { it.code in 32..126 || it.code > 159 }) "« $s »" else null
    }

    fun status(code: Int): String = when (code) {
        133 -> S(R.string.gatt_error_133_the_device_refused)
        8 -> S(R.string.supervision_timeout_8_the_device_moved)
        19 -> S(R.string.disconnected_by_the_device_19)
        22 -> S(R.string.connection_closed_by_this_phone_22)
        62 -> S(R.string.link_establishment_failed_62)
        5, 15 -> S(R.string.authentication_required_pair_the_device_first, code)
        3 -> S(R.string.write_not_permitted_3)
        2 -> S(R.string.read_not_permitted_2)
        else -> S(R.string.gatt_error, code)
    }
}

/**
 * Rings a tag through the Immediate Alert service (0x1802 / 0x2A06, "high alert").
 * Returns false when the device cannot be reached or does not offer the service.
 */
suspend fun ringDevice(context: Context, address: String): Boolean {
    val client = GattClient(context, address)
    return try {
        client.connect(10_000)
        val alert = client.discover().firstOrNull { GattNames.uuid16(it.uuid) == 0x1802 }
            ?.getCharacteristic(GattNames.uuid(0x2A06)) ?: return false
        client.write(alert, byteArrayOf(2))
        kotlinx.coroutines.delay(600)
        true
    } catch (_: GattException) {
        false
    } finally {
        client.close()
    }
}
