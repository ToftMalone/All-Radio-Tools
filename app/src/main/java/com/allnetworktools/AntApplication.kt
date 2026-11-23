package com.allnetworktools

import android.app.Application
import com.allnetworktools.data.BluetoothRepository
import com.allnetworktools.data.CellRepository
import com.allnetworktools.data.GnssRepository
import com.allnetworktools.data.HistoryStore
import com.allnetworktools.data.PermissionsRepository
import com.allnetworktools.data.RadiosRepository
import com.allnetworktools.data.SettingsRepository
import com.allnetworktools.data.WifiRepository

open class AntApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.allnetworktools.util.Res.context = this
        com.allnetworktools.util.DebugLog.init(this)
        com.allnetworktools.data.sdr.TxSettings.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun log(a: android.app.Activity, what: String) = com.allnetworktools.util.DebugLog.d("Lifecycle", "${a.javaClass.simpleName} $what")
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = log(a, "created" + if (b != null) " (restored)" else "")
            override fun onActivityStarted(a: android.app.Activity) = log(a, "started")
            override fun onActivityResumed(a: android.app.Activity) = log(a, "resumed")
            override fun onActivityPaused(a: android.app.Activity) = log(a, "paused")
            override fun onActivityStopped(a: android.app.Activity) = log(a, "stopped")
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = log(a, "destroyed" + if (a.isChangingConfigurations) " (configuration change)" else "")
        })
        startLocale = resources.configuration.locales[0]
    }

    /** Language the process started in: labels built once (enum titles, lists) are in this language. */
    var startLocale: java.util.Locale? = null
        private set

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.allnetworktools.util.DebugLog.w("Memory", "onTrimMemory level $level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.allnetworktools.util.DebugLog.w("Memory", "onLowMemory")
    }

    open val settings by lazy { SettingsRepository(this) }
    open val permissions by lazy { PermissionsRepository(this) }
    open val radios by lazy { RadiosRepository(this) }
    open val wifi by lazy { WifiRepository(this) }
    open val bluetooth by lazy { BluetoothRepository(this) }
    open val cell by lazy { CellRepository(this) }
    open val gnss by lazy { GnssRepository(this) }
    open val history by lazy { HistoryStore(this) }
    open val updater by lazy { com.allnetworktools.update.AppUpdater(this, BuildConfig.VERSION_NAME) }
    open val tles by lazy { com.allnetworktools.data.orbit.TleRepository(this) }
    open val towers by lazy { com.allnetworktools.data.TowerRepository() }
    open val sdr by lazy { com.allnetworktools.data.sdr.SdrRepository(this) }
    open val weatherTles by lazy { com.allnetworktools.data.orbit.WeatherTleRepository(this) }
    open val amateurTles by lazy { com.allnetworktools.data.orbit.WeatherTleRepository(this, "amateur.tle", com.allnetworktools.data.orbit.WeatherTleRepository.AmateurUrl) }
    open val nfc by lazy { com.allnetworktools.data.nfc.NfcRepository(this) }
    open val wifiDirect by lazy { com.allnetworktools.data.WifiDirectRepository(this) }
}
