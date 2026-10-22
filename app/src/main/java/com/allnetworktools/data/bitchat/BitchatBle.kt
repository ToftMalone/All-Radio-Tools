package com.allnetworktools.data.bitchat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BleStatus { Idle, Running, BluetoothOff, NoAdvertiser, Failed }

/**
 * BitChat's Bluetooth LE transport. Like the official apps every phone plays both roles at once: a GATT server
 * advertising the BitChat service (others connect to it and write packets, we notify them), and a GATT client
 * scanning for that service (we connect, subscribe to the characteristic and write packets to it).
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(private val context: Context, private val peerId: String) : MeshLink {
    var mesh: BitchatMesh? = null

    private val _status = MutableStateFlow(BleStatus.Idle)
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    private val serviceUuid = UUID.fromString(Bitchat.SERVICE_UUID)
    private val charUuid = UUID.fromString(Bitchat.CHARACTERISTIC_UUID)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter

    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var scanning = false
    private var started = false
    private val links = ConcurrentHashMap<String, Link>()
    private val pending = ConcurrentHashMap<String, Long>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // ---- per-connection write queue ---------------------------------------------------------------------

    private abstract inner class Link(val id: String) {
        private val queue = ArrayDeque<ByteArray>()
        private var busy = false
        private var busyAt = 0L

        fun enqueue(d: ByteArray) {
            synchronized(this) {
                if (queue.size > 300) queue.removeFirst()
                queue.addLast(d)
            }
            pump()
        }

        fun pump() {
            while (true) {
                val d = synchronized(this) {
                    if (busy && System.currentTimeMillis() - busyAt < 1_500) return
                    queue.removeFirstOrNull()?.also { busy = true; busyAt = System.currentTimeMillis() } ?: run { busy = false; return }
                }
                if (transmit(d)) return
                synchronized(this) { busy = false }
            }
        }

        fun done() {
            synchronized(this) { busy = false }
            pump()
        }

        abstract fun transmit(d: ByteArray): Boolean
        abstract fun close()
    }

    private inner class ServerLink(val device: BluetoothDevice) : Link("S:" + device.address) {
        override fun transmit(d: ByteArray): Boolean {
            val s = server ?: return false
            val c = serverChar ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, c, false, d) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.value = d; s.notifyCharacteristicChanged(device, c, false) }
            }
        }

        override fun close() { server?.cancelConnection(device) }
    }

    private inner class ClientLink(val gatt: BluetoothGatt, val ch: BluetoothGattCharacteristic) : Link("C:" + gatt.device.address) {
        override fun transmit(d: ByteArray): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, d, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; ch.value = d; gatt.writeCharacteristic(ch) }
        }

        override fun close() { gatt.disconnect(); gatt.close() }
    }

    // ---- MeshLink -------------------------------------------------------------------------------------

    override fun send(conn: String, data: ByteArray) {
        links[conn]?.enqueue(data)
    }

    override fun sendAll(data: ByteArray, except: String?) {
        links.forEach { (id, l) -> if (id != except) l.enqueue(data) }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    @Synchronized
    fun start() {
        if (started) return
        val a = adapter
        if (a == null || !a.isEnabled) { _status.value = BleStatus.BluetoothOff; return }
        started = true
        _status.value = BleStatus.Running
        openServer()
        scanCycle()
    }

    @Synchronized
    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        stopAdvertising()
        links.values.toList().forEach { runCatching { it.close() } }
        links.clear(); serverDevices.clear(); pending.clear()
        runCatching { server?.close() }
        server = null; serverChar = null
        _status.value = BleStatus.Idle
    }

    val linkCount get() = links.size

    // ---- GATT server ------------------------------------------------------------------------------------

    private fun openServer() {
        val s = manager.openGattServer(context, serverCallback) ?: run { _status.value = BleStatus.Failed; return }
        server = s
        val ch = BluetoothGattCharacteristic(
            charUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        serverChar = ch
        val svc = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        s.addService(svc)
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS && started) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val id = "S:" + device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevices[device.address] = device
            } else {
                serverDevices.remove(device.address)
                links.remove(id)?.let { mesh?.linkDown(id) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (descriptor.uuid != cccd) return
            val id = "S:" + device.address
            if (value.isNotEmpty() && value[0].toInt() != 0) {
                if (!links.containsKey(id)) {
                    links[id] = ServerLink(device)
                    mesh?.linkUp(id)
                }
            } else {
                links.remove(id)?.let { mesh?.linkDown(id) }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(1, 0))
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid != charUuid || offset != 0) return
            mesh?.receive("S:" + device.address, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            links["S:" + device.address]?.done()
        }
    }

    // ---- advertising -------------------------------------------------------------------------------------

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: run { _status.value = BleStatus.NoAdvertiser; return }
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false).addServiceUuid(ParcelUuid(serviceUuid)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(ParcelUuid(serviceUuid), Bitchat.unhex(peerId)).build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (advertising) runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) _status.value = BleStatus.NoAdvertiser
        }
    }

    // ---- scanning and GATT client ----------------------------------------------------------------------------

    /** Scans in bursts, continuously while nobody is connected, to spare the battery once the mesh is up. */
    private fun scanCycle() {
        if (!started) return
        startScan()
        val on = if (links.isEmpty()) 15_000L else 6_000L
        val off = if (links.isEmpty()) 2_000L else 12_000L
        handler.postDelayed({
            stopScan()
            handler.postDelayed({ scanCycle() }, off)
        }, on)
    }

    private fun startScan() {
        if (scanning) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.isSuccess
    }

    private fun stopScan() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val addr = dev.address
            val now = System.currentTimeMillis()
            if (links.containsKey("C:$addr")) return
            if (pending[addr]?.let { now - it < 20_000 } == true) return
            if ((retryAt[addr] ?: 0L) > now) return
            val theirId = result.scanRecord?.getServiceData(ParcelUuid(serviceUuid))?.takeIf { it.size >= 8 }?.let { Bitchat.hex(it.copyOf(8)) }
            if (theirId == peerId) return
            if (links.keys.count { it.startsWith("C:") } + pending.size >= MAX_CLIENT_LINKS) return
            pending[addr] = now
            handler.post { connect(dev) }
            handler.postDelayed({ if (pending.remove(addr) != null && !links.containsKey("C:$addr")) retryAt[addr] = System.currentTimeMillis() + 15_000 }, 20_000)
        }
    }

    private fun connect(dev: BluetoothDevice) {
        if (!started) return
        dev.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else {
                pending.remove(addr)
                retryAt[addr] = System.currentTimeMillis() + 10_000
                links.remove("C:$addr")?.let { mesh?.linkDown(it.id) }
                runCatching { gatt.close() }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ch = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(cccd) ?: run { gatt.disconnect(); return }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(d) }
            }
            if (!ok) gatt.disconnect()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ch = descriptor.characteristic
            val id = "C:" + gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null) { gatt.disconnect(); return }
            pending.remove(gatt.device.address)
            if (links.containsKey(id)) return
            links[id] = ClientLink(gatt, ch)
            mesh?.linkUp(id)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            links["C:" + gatt.device.address]?.done()
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = characteristic.value ?: return
            mesh?.receive("C:" + gatt.device.address, v)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            mesh?.receive("C:" + gatt.device.address, value)
        }
    }

    private companion object {
        const val MAX_CLIENT_LINKS = 8
    }
}
