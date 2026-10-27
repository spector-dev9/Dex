package io.github.spectordev9.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {
    // The graph outlives the activity's rotations within the process.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appRuntime: AppRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appRuntime = AppRuntime.obtain(applicationContext, appScope)
        // Install/verify the pinned runtime, then start the supervised server.
        appRuntime.ensureRunning()
        setContent {
            OpenCodeShell(
                ptyService = appRuntime.terminal,
                runtimeStateFlow = appRuntime.runtimeState,
                serverStateFlow = appRuntime.serverState,
                onOpenLicenses = {
                    startActivity(android.content.Intent(this, LicensesActivity::class.java))
                },
            )
        }
    }
}
