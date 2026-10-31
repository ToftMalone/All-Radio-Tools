package com.allnetworktools.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.allnetworktools.Blocker
import com.allnetworktools.MainViewModel
import com.allnetworktools.data.PermGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.allnetworktools.R
import com.allnetworktools.util.S

/** Snackbar message holder (inverse surface, bottom, 2.4 s). */
class Toaster {
    var message by mutableStateOf<String?>(null)
        private set
    var serial by mutableStateOf(0)
        private set

    fun show(text: String) {
        message = text
        serial++
    }

    fun dismiss(forSerial: Int) {
        if (serial == forSerial) message = null
    }
}

class AppActions(
    private val context: Context,
    private val vm: MainViewModel,
    val toaster: Toaster,
    private val requestPermissions: (Array<String>) -> Unit,
    private val createFile: (SaveRequest) -> Unit = {},
) {
    private var pendingSave: (() -> ByteArray)? = null
    fun toast(text: String) = toaster.show(text)

    private fun activity(): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private fun launch(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(S(R.string.screen_unavailable_on_this_device)) }
    }

    fun openAppSettings() = launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    /** True if Android will no longer show the dialog for this group (denied twice or "don't ask again"). */
    fun isPermanentlyDenied(group: PermGroup): Boolean {
        val act = activity() ?: return false
        val asked = group.name in (vm.settings.value?.askedPermissions ?: emptySet())
        val missing = group.permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        return asked && missing.isNotEmpty() && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
    }

    fun request(group: PermGroup) {
        when {
            group.permissions.isEmpty() -> vm.refreshPermissions()
            isPermanentlyDenied(group) -> {
                openAppSettings()
                toast(S(R.string.allow_access_in_android_settings_permissions))
            }
            else -> {
                vm.markAsked(group)
                requestPermissions(group.permissions.toTypedArray())
            }
        }
    }

    fun openWifiPanel() = launch(Intent(Settings.Panel.ACTION_WIFI))
    fun openWifiSettings() = launch(Intent(Settings.ACTION_WIFI_SETTINGS))
    fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openAirplaneSettings() = launch(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
    fun openLocationSettings() = launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    fun openNfcSettings() = launch(Intent(Settings.ACTION_NFC_SETTINGS))
    fun openNetworkSettings() = launch(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))

    fun enableBluetooth() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            request(PermGroup.Nearby)
        } else {
            launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    fun share(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, title))
    }

    /**
     * Asks Android where to save [fileName], then writes what [content] produces there. The app keeps no copy: the bytes
     * go straight to the file the user chose. [content] runs off the main thread, so it must work on a snapshot.
     */
    fun saveFile(fileName: String, mime: String, content: () -> ByteArray) {
        pendingSave = content
        runCatching { createFile(SaveRequest(fileName, mime)) }.onFailure { pendingSave = null; toast(S(R.string.saving_unavailable_on_this_device)) }
    }

    internal fun onFileChosen(uri: Uri?) {
        val content = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        vm.viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content()) } }.isSuccess
            }
            toast(if (ok) S(R.string.file_saved) else S(R.string.could_not_write_the_file))
        }
    }

    fun openUrl(url: String) = launch(Intent(Intent.ACTION_VIEW, url.toUri()))

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(S(R.string.copied, label))
    }

    /** Primary action for a blocked network. */
    fun resolve(blocker: Blocker) = when (blocker) {
        Blocker.WifiOff -> openWifiPanel()
        Blocker.BluetoothOff -> enableBluetooth()
        Blocker.NearbyPermission -> request(PermGroup.Nearby)
        Blocker.Airplane -> openAirplaneSettings()
        Blocker.PhonePermission -> request(PermGroup.Phone)
        Blocker.NoSim -> openNetworkSettings()
        Blocker.LocationPermission -> request(PermGroup.Location)
        Blocker.LocationOff -> openLocationSettings()
        Blocker.NfcOff -> openNfcSettings()
        Blocker.NoHardware, Blocker.SdrMissing -> Unit
    }
}

data class SaveRequest(val fileName: String, val mime: String)

/** "Create document" with the MIME type chosen per call rather than fixed with the launcher. */
private class CreateFile : ActivityResultContract<SaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

@Composable
fun rememberAppActions(vm: MainViewModel, toaster: Toaster): AppActions {
    val context = LocalContext.current
    val vmState by rememberUpdatedState(vm)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vmState.refreshPermissions()
    }
    val holder = remember { arrayOfNulls<AppActions>(1) }
    val saver = rememberLauncherForActivityResult(CreateFile()) { holder[0]?.onFileChosen(it) }
    return remember(context, vm, toaster) {
        AppActions(context, vm, toaster, { launcher.launch(it) }, { saver.launch(it) }).also { holder[0] = it }
    }
}
