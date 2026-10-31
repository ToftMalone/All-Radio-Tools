package com.allnetworktools.data.nfc

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import com.allnetworktools.data.broadcastFlow
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What the phone's NFC can do, read once. */
data class NfcCaps(
    /** Card emulation by apps (HCE): the phone acts as a contactless card. */
    val hce: Boolean,
    val hceF: Boolean,
    /** A secure element (SIM or embedded) can hold cards, e.g. for payment. */
    val offHostUicc: Boolean,
    val offHostEse: Boolean,
    /** "Secure NFC": NFC payments only with the screen unlocked; null before Android 10. */
    val secureNfc: Boolean?,
    val secureNfcSupported: Boolean,
    /** Android 15: watching readers' polling without answering. */
    val observeMode: Boolean?,
)

/**
 * The NFC controller in reader mode: while an NFC page is open and the app is in front, every tag held to the phone is
 * delivered to [onTag] (on the NFC thread, so the page can talk to it right away), and Android neither beeps nor opens
 * another app for it.
 */
open class NfcRepository(private val context: Context) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    open val hasNfc: Boolean = adapter != null

    open val enabled: Flow<Boolean> =
        if (adapter == null) flowOf(false) else broadcastFlow(context, NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) { adapter.isEnabled }

    open fun caps(): NfcCaps {
        val pm = context.packageManager
        val a = adapter
        return NfcCaps(
            hce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
            hceF = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION_NFCF),
            offHostUicc = pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC),
            offHostEse = pm.hasSystemFeature(PackageManager.FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE),
            secureNfc = a?.let { runCatching { if (it.isSecureNfcSupported) it.isSecureNfcEnabled else null }.getOrNull() },
            secureNfcSupported = a?.let { runCatching { it.isSecureNfcSupported }.getOrDefault(false) } == true,
            observeMode = if (Build.VERSION.SDK_INT >= 35) a?.let { runCatching { it.isObserveModeSupported }.getOrNull() } else null,
        )
    }

    private var activity = WeakReference<Activity>(null)
    private var resumed = false
    private var handler: ((Tag) -> Unit)? = null
    private var on = false

    /** From the activity's onResume / onPause: reader mode needs a resumed activity. */
    fun attach(a: Activity) { activity = WeakReference(a); resumed = true; update() }
    fun detach(a: Activity) { if (activity.get() === a) { update(off = true); resumed = false } }

    /** The page that wants the tags; null when it closes. Main thread. */
    fun listen(onTag: ((Tag) -> Unit)?) {
        handler = onTag
        update()
    }

    private fun update(off: Boolean = false) {
        val a = activity.get() ?: return
        val nfc = adapter ?: return
        val want = !off && resumed && handler != null
        if (want == on) return
        on = want
        runCatching {
            if (want) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
                // A short presence check, so a tag taken away is noticed within a quarter of a second.
                val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
                nfc.enableReaderMode(a, { tag -> handler?.invoke(tag) }, flags, extras)
            } else {
                nfc.disableReaderMode(a)
            }
        }
    }
}
