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
        startLocale = resources.configuration.locales[0]
    }

    /** Language the process started in: labels built once (enum titles, lists) are in this language. */
    var startLocale: java.util.Locale? = null
        private set

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
    open val wifiDirect by lazy { com.allnetworktools.data.WifiDirectRepository(this) }
}
