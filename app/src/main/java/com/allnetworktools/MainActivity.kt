package com.allnetworktools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.allnetworktools.ui.AntApp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A language change (phone or per-app setting) only recreates the activity, while many labels were
        // built once in the old language: start a fresh process so every text follows the new one.
        val started = (application as? AntApplication)?.startLocale
        if (started != null && started != resources.configuration.locales[0]) {
            startActivity(Intent.makeRestartActivityTask(componentName))
            Runtime.getRuntime().exit(0)
            return
        }
        enableEdgeToEdge()
        setContent { AntApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }
}
