package io.github.spectordev9.opencode.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

class SpikeForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            recordHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SpikeReport.logEvent("S7-service", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val startingNewSoak = intent?.action == ACTION_START_SOAK
        if (!startingNewSoak) recordServiceRestartIfNeeded()
        initializeSoakIfNeeded(forceNew = startingNewSoak)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode spike service")
            .setContentText("S7 background-survival probe is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        SpikeReport.logEvent("S7-service", "startForeground onStartCommand startId=$startId")
        handler.removeCallbacks(heartbeat)
        recordHeartbeat()
        handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        SpikeReport.logEvent("S7-service", "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        soakPreferences().edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_STOPPED_AT, System.currentTimeMillis())
            .apply()
        SpikeReport.logEvent("S7-service", "onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenCode spike",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Pre-G0 foreground-service feasibility probe"
        manager.createNotificationChannel(channel)
    }

    private fun initializeSoakIfNeeded(forceNew: Boolean) {
        val preferences = soakPreferences()
        val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
        val wasActive = preferences.getBoolean(KEY_ACTIVE, false)
        if (forceNew || startedAt == 0L || !wasActive) {
            val now = System.currentTimeMillis()
            preferences.edit()
                .putLong(KEY_STARTED_AT, now)
                .putLong(KEY_STARTED_ELAPSED, SystemClock.elapsedRealtime())
                .putLong(KEY_LAST_HEARTBEAT_AT, now)
                .putLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
                .putLong(KEY_STOPPED_AT, 0L)
                .putInt(KEY_HEARTBEATS, 0)
                .putInt(KEY_SERVICE_RESTARTS, 0)
                .putInt(KEY_THERMAL_SAMPLES, 0)
                .putInt(KEY_THERMAL_BELOW_MODERATE, 0)
                .putInt(KEY_THERMAL_MAX, THERMAL_UNKNOWN)
                .putBoolean(KEY_COMPLETED, false)
                .putBoolean(KEY_ACTIVE, true)
                .apply()
        }
    }

    private fun recordServiceRestartIfNeeded() {
        val preferences = soakPreferences()
        if (!preferences.getBoolean(KEY_ACTIVE, false) ||
            preferences.getLong(KEY_STARTED_AT, 0L) == 0L
        ) {
            return
        }
        val restartCount = preferences.getInt(KEY_SERVICE_RESTARTS, 0) + 1
        preferences.edit().putInt(KEY_SERVICE_RESTARTS, restartCount).apply()
        SpikeReport.logEvent("S7-service-restarted", "service restart count=$restartCount")
    }

    private fun recordHeartbeat() {
        val preferences = soakPreferences()
        val now = System.currentTimeMillis()
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val thermalStatus = currentThermalStatus()
        val previousMax = preferences.getInt(KEY_THERMAL_MAX, THERMAL_UNKNOWN)
        val editor = preferences.edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, now)
            .putLong(KEY_LAST_HEARTBEAT_ELAPSED, elapsedRealtime)
            .putInt(KEY_HEARTBEATS, preferences.getInt(KEY_HEARTBEATS, 0) + 1)
        if (thermalStatus != THERMAL_UNKNOWN) {
            editor
                .putInt(KEY_THERMAL_SAMPLES, preferences.getInt(KEY_THERMAL_SAMPLES, 0) + 1)
                .putInt(
                    KEY_THERMAL_BELOW_MODERATE,
                    preferences.getInt(KEY_THERMAL_BELOW_MODERATE, 0) +
                        if (thermalStatus < PowerManager.THERMAL_STATUS_MODERATE) 1 else 0,
                )
                .putInt(KEY_THERMAL_MAX, maxOf(previousMax, thermalStatus))
        }
        editor.apply()
    }

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(PowerManager::class.java).currentThermalStatus
        } else {
            THERMAL_UNKNOWN
        }

    private fun soakPreferences() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)

    companion object {
        const val CHANNEL_ID = "spike-foreground"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_SOAK = "io.github.spectordev9.opencode.spike.START_S7_SOAK"
        private const val PREFERENCES = "s7-soak"
        private const val KEY_STARTED_AT = "started-at"
        private const val KEY_STARTED_ELAPSED = "started-elapsed"
        private const val KEY_LAST_HEARTBEAT_AT = "last-heartbeat-at"
        private const val KEY_LAST_HEARTBEAT_ELAPSED = "last-heartbeat-elapsed"
        private const val KEY_STOPPED_AT = "stopped-at"
        private const val KEY_HEARTBEATS = "heartbeats"
        private const val KEY_SERVICE_RESTARTS = "service-restarts"
        private const val KEY_THERMAL_SAMPLES = "thermal-samples"
        private const val KEY_THERMAL_BELOW_MODERATE = "thermal-below-moderate"
        private const val KEY_THERMAL_MAX = "thermal-max"
        private const val KEY_COMPLETED = "completed"
        private const val KEY_COMPLETED_PASS = "completed-pass"
        private const val KEY_COMPLETED_DETAIL = "completed-detail"
        private const val KEY_COMPLETED_DURATION = "completed-duration"
        private const val KEY_ACTIVE = "active"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_STALE_MS = HEARTBEAT_INTERVAL_MS * 2
        private const val THERMAL_UNKNOWN = -1
        private const val MINIMUM_SOAK_MS = 30 * 60 * 1000L

        fun soakStatus(context: android.content.Context): String {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
            if (startedAt == 0L) return "No S7 background run has been started."
            val currentElapsed = SystemClock.elapsedRealtime()
            val durationMs =
                (currentElapsed - preferences.getLong(KEY_STARTED_ELAPSED, currentElapsed)).coerceAtLeast(0L)
            val lastHeartbeat = preferences.getLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
            val alive = preferences.getBoolean(KEY_ACTIVE, false) &&
                currentElapsed - lastHeartbeat in 0..HEARTBEAT_STALE_MS
            return "elapsed=${durationMs / 60_000} min; heartbeats=${preferences.getInt(KEY_HEARTBEATS, 0)}; " +
                "serviceRestarts=${preferences.getInt(KEY_SERVICE_RESTARTS, 0)}; serviceHeartbeatFresh=$alive"
        }

        fun soakResult(context: android.content.Context): ProbeRecord? {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
            if (startedAt == 0L) return null
            if (preferences.getBoolean(KEY_COMPLETED, false)) {
                return ProbeRecord(
                    id = "S7-background-survival",
                    pass = preferences.getBoolean(KEY_COMPLETED_PASS, false),
                    detail = preferences.getString(KEY_COMPLETED_DETAIL, "completed result unavailable")
                        .orEmpty(),
                    durationMs = preferences.getLong(KEY_COMPLETED_DURATION, 0L),
                )
            }

            val nowElapsed = SystemClock.elapsedRealtime()
            val elapsedMs = (nowElapsed - preferences.getLong(KEY_STARTED_ELAPSED, nowElapsed))
                .coerceAtLeast(0L)
            val lastHeartbeat = preferences.getLong(KEY_LAST_HEARTBEAT_ELAPSED, 0L)
            val serviceAlive = preferences.getBoolean(KEY_ACTIVE, false) &&
                nowElapsed - lastHeartbeat in 0..HEARTBEAT_STALE_MS
            val thermalSamples = preferences.getInt(KEY_THERMAL_SAMPLES, 0)
            val belowModerate = preferences.getInt(KEY_THERMAL_BELOW_MODERATE, 0)
            val maxThermal = preferences.getInt(KEY_THERMAL_MAX, THERMAL_UNKNOWN)
            val serviceRestarts = preferences.getInt(KEY_SERVICE_RESTARTS, 0)
            val durationPassed = elapsedMs >= MINIMUM_SOAK_MS
            return ProbeRecord(
                id = "S7-background-survival",
                pass = durationPassed && serviceAlive && serviceRestarts == 0,
                detail = "elapsedMs=$elapsedMs; minimumRequiredMs=$MINIMUM_SOAK_MS; " +
                    "heartbeats=${preferences.getInt(KEY_HEARTBEATS, 0)}; serviceRestarts=$serviceRestarts; " +
                    "serviceHeartbeatFresh=$serviceAlive; " +
                    "thermalSamples=$thermalSamples; thermalBelowModerate=$belowModerate; " +
                    "maximumThermalStatus=$maxThermal; screenOff=not-measured; this is one harness-service run, " +
                    "not the agent-task 19/20 device-matrix acceptance test",
                durationMs = elapsedMs,
            )
        }

        fun completeSoak(context: android.content.Context): ProbeRecord? {
            val record = soakResult(context) ?: return null
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            preferences.edit()
                .putBoolean(KEY_COMPLETED, true)
                .putBoolean(KEY_COMPLETED_PASS, record.pass)
                .putString(KEY_COMPLETED_DETAIL, record.detail)
                .putLong(KEY_COMPLETED_DURATION, record.durationMs)
                .apply()
            return record
        }
    }
}
