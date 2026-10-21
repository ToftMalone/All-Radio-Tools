package com.allnetworktools.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.allnetworktools.ui.theme.Sym
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import com.allnetworktools.R
import com.allnetworktools.util.S

enum class RadioTech(val short: String) { NR("NR"), LTE("LTE"), WCDMA("3G"), GSM("2G") }

data class CellMeasure(
    val tech: RadioTech,
    val registered: Boolean,
    val band: String?,
    val arfcnLabel: String?,
    val arfcn: Int?,
    val pci: Int?,
    val tac: Int?,
    val cellId: Long?,
    val nodeId: Long?,
    val sector: Int?,
    val mcc: String?,
    val mnc: String?,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val rssi: Int?,
    val cqi: Int?,
    val timingAdvance: Int?,
    val bandwidthKhz: Int?,
    val downlinkMhz: Double?,
    /** True when [rssi] is computed from RSRP and RSRQ because the modem does not report it (5G NR). */
    val rssiEstimated: Boolean = false,
) {
    /** Key identifying the same physical cell across updates. */
    val key: String get() = "${tech.name}-$arfcn-$pci"

    /** Primary level, dBm: RSRP for NR/LTE, RSCP/RSSI otherwise. */
    val level: Int? get() = rsrp ?: rssi
}

data class SimInfo(
    val slot: Int,
    val subId: Int,
    val operator: String,
    val roles: List<String>,
    val embedded: Boolean,
    val tech: String,
    val bars: Int,
    val isDefaultData: Boolean,
)

data class CellState(
    val operator: String?,
    val simSlot: Int?,
    val techLabel: String,
    val techLong: String,
    val techBig: String,
    val roaming: Boolean,
    val voiceAndData: Boolean,
    val serving: CellMeasure?,
    val neighbors: List<CellMeasure>,
    val servingBandwidthsKhz: List<Int>,
    val signalLevel: Int,
    val hasService: Boolean,
)

/** RSSI (dBm) from SS-RSRP and SS-RSRQ, or null when either is missing. */
internal fun nrRssi(rsrp: Int?, rsrq: Int?): Int? =
    if (rsrp == null || rsrq == null) null else Math.round(rsrp + 10 * Math.log10(20.0) - rsrq).toInt()

private fun nrArfcnToMhz(n: Int): Double = when {
    n < 600000 -> 0.005 * n
    n < 2016667 -> 3000 + 0.015 * (n - 600000)
    else -> 24250.08 + 0.06 * (n - 2016667)
}

private fun Int.validOr(): Int? = takeIf { it != Int.MAX_VALUE && it != CellInfo.UNAVAILABLE }
private fun Long.validLong(): Long? = takeIf { it != CellInfo.UNAVAILABLE_LONG && it != Long.MAX_VALUE }

private fun measure(info: CellInfo): CellMeasure? = when (info) {
    is CellInfoNr -> {
        val id = info.cellIdentity as CellIdentityNr
        val ss = info.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci.validLong()
        val arfcn = id.nrarfcn.validOr()
        CellMeasure(
            tech = RadioTech.NR, registered = info.isRegistered,
            band = id.bands.firstOrNull()?.let { "n$it" },
            arfcnLabel = arfcn?.let { "NR-ARFCN" }, arfcn = arfcn,
            pci = id.pci.validOr(), tac = id.tac.validOr(), cellId = nci,
            nodeId = nci?.shr(12), sector = nci?.and(0xFFF)?.toInt(),
            mcc = id.mccString, mnc = id.mncString,
            rsrp = ss.ssRsrp.validOr() ?: ss.csiRsrp.validOr(),
            rsrq = ss.ssRsrq.validOr() ?: ss.csiRsrq.validOr(),
            sinr = ss.ssSinr.validOr() ?: ss.csiSinr.validOr(),
            // Android exposes no RSSI for NR: SS-RSRQ = N × SS-RSRP / RSSI with N = 20 RB (the SSB width),
            // hence RSSI ≈ RSRP + 10·log10(20) − RSRQ.
            rssi = nrRssi(ss.ssRsrp.validOr() ?: ss.csiRsrp.validOr(), ss.ssRsrq.validOr() ?: ss.csiRsrq.validOr()),
            cqi = ss.csiCqiReport.firstOrNull(),
            timingAdvance = if (Build.VERSION.SDK_INT >= 34) ss.timingAdvanceMicros.validOr() else null,
            bandwidthKhz = null,
            downlinkMhz = arfcn?.let(::nrArfcnToMhz),
            rssiEstimated = true,
        )
    }
    is CellInfoLte -> {
        val id = info.cellIdentity
        val ss = info.cellSignalStrength
        val ci = id.ci.validOr()?.toLong()
        CellMeasure(
            tech = RadioTech.LTE, registered = info.isRegistered,
            band = id.bands.firstOrNull()?.let { "B$it" },
            arfcnLabel = "EARFCN", arfcn = id.earfcn.validOr(),
            pci = id.pci.validOr(), tac = id.tac.validOr(), cellId = ci,
            nodeId = ci?.shr(8), sector = ci?.and(0xFF)?.toInt(),
            mcc = id.mccString, mnc = id.mncString,
            rsrp = ss.rsrp.validOr(), rsrq = ss.rsrq.validOr(),
            sinr = ss.rssnr.validOr()?.let { if (it > 60 || it < -30) it / 10 else it },
            rssi = ss.rssi.validOr(), cqi = ss.cqi.validOr(), timingAdvance = ss.timingAdvance.validOr(),
            bandwidthKhz = id.bandwidth.validOr(),
            downlinkMhz = null,
        )
    }
    is CellInfoWcdma -> {
        val id: CellIdentityWcdma = info.cellIdentity
        val ss = info.cellSignalStrength
        CellMeasure(
            tech = RadioTech.WCDMA, registered = info.isRegistered, band = null,
            arfcnLabel = "UARFCN", arfcn = id.uarfcn.validOr(), pci = id.psc.validOr(), tac = id.lac.validOr(),
            cellId = id.cid.validOr()?.toLong(), nodeId = id.cid.validOr()?.toLong()?.shr(16), sector = null,
            mcc = id.mccString, mnc = id.mncString, rsrp = null, rsrq = ss.ecNo.validOr(),
            sinr = null, rssi = ss.dbm.validOr(), cqi = null, timingAdvance = null, bandwidthKhz = null, downlinkMhz = null,
        )
    }
    is CellInfoGsm -> {
        val id: CellIdentityGsm = info.cellIdentity
        val ss = info.cellSignalStrength
        CellMeasure(
            tech = RadioTech.GSM, registered = info.isRegistered, band = null,
            arfcnLabel = "ARFCN", arfcn = id.arfcn.validOr(), pci = id.bsic.validOr(), tac = id.lac.validOr(),
            cellId = id.cid.validOr()?.toLong(), nodeId = null, sector = null,
            mcc = id.mccString, mnc = id.mncString, rsrp = null, rsrq = null, sinr = null,
            rssi = ss.dbm.validOr(), cqi = null, timingAdvance = ss.timingAdvance.validOr(), bandwidthKhz = null, downlinkMhz = null,
        )
    }
    else -> null
}

/** Serving-cell levels without identities; available even without location access. */
private fun measureFromStrength(s: SignalStrength): CellMeasure? {
    val css = s.cellSignalStrengths
    val nr = css.filterIsInstance<CellSignalStrengthNr>().firstOrNull()
    val lte = css.filterIsInstance<android.telephony.CellSignalStrengthLte>().firstOrNull()
    fun empty(tech: RadioTech) = CellMeasure(
        tech = tech, registered = true, band = null, arfcnLabel = null, arfcn = null, pci = null, tac = null,
        cellId = null, nodeId = null, sector = null, mcc = null, mnc = null, rsrp = null, rsrq = null, sinr = null,
        rssi = null, cqi = null, timingAdvance = null, bandwidthKhz = null, downlinkMhz = null,
    )
    return when {
        nr != null && nr.ssRsrp.validOr() != null -> empty(RadioTech.NR).copy(rsrp = nr.ssRsrp.validOr(), rsrq = nr.ssRsrq.validOr(), sinr = nr.ssSinr.validOr())
        lte != null -> empty(RadioTech.LTE).copy(rsrp = lte.rsrp.validOr(), rsrq = lte.rsrq.validOr(), sinr = lte.rssnr.validOr(), rssi = lte.rssi.validOr(), cqi = lte.cqi.validOr())
        else -> null
    }
}

@SuppressLint("MissingPermission")
open class CellRepository(private val context: Context) {
    private val baseTm = context.getSystemService(TelephonyManager::class.java)
    private val subs = context.getSystemService(SubscriptionManager::class.java)

    open val hasTelephony: Boolean get() = baseTm != null && baseTm.phoneType != TelephonyManager.PHONE_TYPE_NONE

    open fun simReady(): Boolean = baseTm?.simState == TelephonyManager.SIM_STATE_READY ||
        runCatching { (subs?.activeSubscriptionInfoCount ?: 0) > 0 }.getOrDefault(false)

    private fun defaultTm(): TelephonyManager? {
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) baseTm?.createForSubscriptionId(id) else baseTm
    }

    /** MCC+MNC of the default data SIM and of the network it is registered on; null when unknown. */
    open fun plmns(): Pair<String?, String?> {
        val tm = defaultTm() ?: return null to null
        val sim = runCatching { tm.simOperator }.getOrNull()?.takeIf { it.length >= 5 }
        val net = runCatching { tm.networkOperator }.getOrNull()?.takeIf { it.length >= 5 }
        return sim to net
    }

    /**
     * Serving and neighbour cells of the default data SIM. Cell info needs READ_PHONE_STATE and
     * ACCESS_FINE_LOCATION; fresh measurements are requested every [refreshMs].
     */
    open fun cells(refreshMs: Long): Flow<CellState> = callbackFlow {
        val tm = defaultTm() ?: run { awaitClose { }; return@callbackFlow }
        var cells: List<CellInfo> = runCatching { tm.allCellInfo }.getOrNull().orEmpty()
        var display: TelephonyDisplayInfo? = null
        var service: ServiceState? = runCatching { tm.serviceState }.getOrNull()
        var strength: SignalStrength? = tm.signalStrength
        fun emit() = trySend(buildState(tm, cells, display, service, strength))

        val cb = object : TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                cells = cellInfo; emit()
            }

            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                display = info; emit()
            }

            override fun onServiceStateChanged(state: ServiceState) {
                service = state; emit()
            }

            override fun onSignalStrengthsChanged(s: SignalStrength) {
                strength = s; emit()
            }
        }
        runCatching { tm.registerTelephonyCallback(context.mainExecutor, cb) }
        emit()
        val poll = launch {
            while (true) {
                delay(refreshMs.coerceAtLeast(500))
                runCatching {
                    tm.requestCellInfoUpdate(context.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            cells = cellInfo; emit()
                        }
                    })
                }
            }
        }
        awaitClose {
            poll.cancel()
            runCatching { tm.unregisterTelephonyCallback(cb) }
        }
    }

    private fun buildState(
        tm: TelephonyManager,
        cells: List<CellInfo>,
        display: TelephonyDisplayInfo?,
        service: ServiceState?,
        strength: SignalStrength?,
    ): CellState {
        val all = cells.mapNotNull(::measure)
        val serving = all.firstOrNull { it.registered && it.tech == RadioTech.NR } ?: all.firstOrNull { it.registered }
            ?: strength?.let(::measureFromStrength)
        val nsa = display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
        val net = display?.networkType ?: runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val (label, long, big) = when {
            net == TelephonyManager.NETWORK_TYPE_NR -> Triple("5G SA", "STANDALONE", "5G")
            display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> Triple("5G+", "NON-STANDALONE", "5G+")
            nsa -> Triple("5G NSA", "NON-STANDALONE", "5G")
            display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> Triple("4G+", "LTE ADVANCED PRO", "4G+")
            display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> Triple("4G+", S(R.string.aggregation), "4G+")
            net == TelephonyManager.NETWORK_TYPE_LTE || net == TelephonyManager.NETWORK_TYPE_IWLAN -> Triple("4G", "LTE", "4G")
            net in listOf(TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA) -> Triple("3G", "UMTS", "3G")
            net in listOf(TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM) -> Triple("2G", "GSM", "2G")
            else -> Triple("—", S(R.string.out_of_service), "—")
        }
        val inService = service?.state == ServiceState.STATE_IN_SERVICE
        val bandwidths = service?.cellBandwidths?.toList().orEmpty()
        val operator = service?.operatorAlphaShort?.takeIf { it.isNotBlank() } ?: tm.networkOperatorName?.takeIf { it.isNotBlank() }
        return CellState(
            operator = operator,
            simSlot = runCatching { subs?.getActiveSubscriptionInfo(SubscriptionManager.getDefaultDataSubscriptionId())?.simSlotIndex?.plus(1) }.getOrNull(),
            techLabel = label, techLong = long, techBig = big,
            roaming = service?.roaming == true,
            voiceAndData = runCatching { if (Build.VERSION.SDK_INT >= 35) tm.isDeviceVoiceCapable else @Suppress("DEPRECATION") tm.isVoiceCapable }.getOrDefault(true),
            serving = serving?.let { s -> if (s.bandwidthKhz == null && bandwidths.isNotEmpty()) s.copy(bandwidthKhz = bandwidths.first()) else s },
            neighbors = all.filter { it !== serving && !it.registered }.sortedByDescending { it.level ?: -200 },
            servingBandwidthsKhz = bandwidths,
            signalLevel = strength?.level ?: 0,
            hasService = inService,
        )
    }

    open fun sims(): List<SimInfo> {
        val list = runCatching { subs?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val dataId = SubscriptionManager.getDefaultDataSubscriptionId()
        val voiceId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        val smsId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return list.map { s ->
            val tm = baseTm?.createForSubscriptionId(s.subscriptionId)
            val roles = buildList {
                if (s.subscriptionId == dataId) add(S(R.string.mobile_data))
                if (s.subscriptionId == voiceId) add(S(R.string.calls))
                if (s.subscriptionId == smsId) add("SMS")
            }
            val type = runCatching { tm?.dataNetworkType }.getOrNull() ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            SimInfo(
                slot = s.simSlotIndex + 1,
                subId = s.subscriptionId,
                operator = s.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: s.displayName?.toString() ?: "SIM ${s.simSlotIndex + 1}",
                roles = roles,
                embedded = s.isEmbedded,
                tech = techShort(type),
                bars = runCatching { tm?.signalStrength?.level }.getOrNull() ?: 0,
                isDefaultData = s.subscriptionId == dataId,
            )
        }.sortedBy { it.slot }
    }

    private fun techShort(type: Int) = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> S(R.string.idle)
        else -> "—"
    }

    companion object {
        fun barsIcon(level: Int): String = when {
            level >= 3 -> Sym.CellBars3
            level == 2 -> Sym.CellBars2
            else -> Sym.CellBars1
        }
    }
}
