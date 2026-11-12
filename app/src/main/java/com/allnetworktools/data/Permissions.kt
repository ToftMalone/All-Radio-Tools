package com.allnetworktools.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PermGroup(val permissions: List<String>) {
    Location(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Nearby(
        buildList {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        },
    ),
    Phone(listOf(Manifest.permission.READ_PHONE_STATE)),

    /** Android 17 blocks the local network (LAN scan, ping, ports…) without it, and shows a device picker instead. */
    LocalNetwork(if (Build.VERSION.SDK_INT >= 37) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()),

    /** The handover journal's notification, which also carries its stop button. */
    Notifications(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
}

data class PermissionSnapshot(val granted: Set<PermGroup>) {
    operator fun contains(g: PermGroup) = g in granted
    val location get() = PermGroup.Location in granted
    val nearby get() = PermGroup.Nearby in granted
    val phone get() = PermGroup.Phone in granted
}

open class PermissionsRepository(private val context: Context) {
    private val _state = MutableStateFlow(read())
    open val state: StateFlow<PermissionSnapshot> = _state.asStateFlow()

    open fun refresh() {
        _state.value = read()
    }

    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun read(): PermissionSnapshot {
        val granted = PermGroup.entries.filter { g ->
            when (g) {
                PermGroup.Location -> has(Manifest.permission.ACCESS_FINE_LOCATION)
                else -> g.permissions.all(::has)
            }
        }.toSet()
        return PermissionSnapshot(granted)
    }
}
