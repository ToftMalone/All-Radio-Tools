package com.allnetworktools

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.allnetworktools.model.Network
import com.allnetworktools.model.Tool
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
        if (savedInstanceState == null) open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    /** A notification can ask for a tool directly. */
    private fun open(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN) == OPEN_BITCHAT) {
            vm.navigate { it.copy(network = Network.Bluetooth, page = Page.ToolPage(Tool.BitChat)) }
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_BITCHAT = "bitchat"
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }
}
