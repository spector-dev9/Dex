package io.github.spectordev9.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
                chat = appRuntime.chat,
                files = appRuntime.files,
                git = appRuntime.git,
                uiPrefs = appRuntime.uiPrefs,
                profiles = appRuntime.profiles,
                cleaner = appRuntime.cleaner,
                workspace = appRuntime.workspace,
                advanced = appRuntime.advanced,
                runtimeStateFlow = appRuntime.runtimeState,
                serverStateFlow = appRuntime.serverState,
                onOpenLicenses = {
                    startActivity(android.content.Intent(this, LicensesActivity::class.java))
                },
                onRetryRuntime = { appRuntime.ensureRunning() },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    override fun onResume() {
        super.onResume()
        appRuntime.onForeground()
    }

    private companion object {
        const val NOTIFICATION_REQUEST = 4101
    }
}
