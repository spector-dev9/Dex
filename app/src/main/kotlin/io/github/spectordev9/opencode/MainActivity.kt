package io.github.spectordev9.opencode

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private val appRuntime: AppRuntime by lazy {
        ViewModelProvider(this)[MainViewModel::class.java].runtime
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install/verify the pinned runtime, then start the supervised server.
        appRuntime.ensureRunning()
        handleDeepLink(intent)
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
                onConnectProfile = { name -> appRuntime.connectProfile(name) },
                onDisconnectProfile = { appRuntime.disconnectProfile() },
                providerSettings = appRuntime.providerSettings,
                onApplyProviderSettings = { appRuntime.applyProviderSettings() },
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "opencode" || data.host != "session") return
        val sessionID = data.pathSegments.firstOrNull() ?: return
        appRuntime.openSession(sessionID)
    }

    private companion object {
        const val NOTIFICATION_REQUEST = 4101
    }
}
