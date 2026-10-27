package io.github.spectordev9.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.spectordev9.opencode.app.bridge.InProcessBridgeTransport

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val transport = remember { InProcessBridgeTransport() }
            OpenCodeShell(
                onOpenLicenses = {
                    startActivity(android.content.Intent(this, LicensesActivity::class.java))
                },
                bridgeTransport = transport
            )
        }
    }
}
