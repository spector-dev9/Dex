package io.github.spectordev9.opencode.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class G3LongRunService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val notification = buildNotification("G3 long run starting")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (action == null || !running.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:g3-long-run").apply {
            setReferenceCounted(false)
            acquire(45 * 60_000L)
        }
        Thread {
            val record = try {
                when (action) {
                    ACTION_LC3 -> {
                        val runs = intent?.getIntExtra(EXTRA_RUNS, 50) ?: 50
                        updateNotification("LC-3 running $runs SIGKILL restarts")
                        G3RecoveryChecks.runLc3(this, runs) { message ->
                            updateNotification(message)
                            writeStatus("LC-3", message, true, null)
                        }
                    }
                    ACTION_LC4 -> {
                        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 10) ?: 10
                        updateNotification("LC-4 idle sampling for $minutes minutes")
                        G3RecoveryChecks.runLc4(this, minutes) { message ->
                            updateNotification(message)
                            writeStatus("LC-4", message, true, null)
                        }
                    }
                    else -> null
                }
            } catch (t: Throwable) {
                ProbeRecord(action ?: "G3-long-run", false, "long run failed: ${t.message}")
            }
            if (record != null) {
                SpikeReport.upsert(record)
                writeStatus(record.id, record.detail, false, record)
                updateNotification("${record.id} ${if (record.pass) "PASS" else "FAIL"}")
            }
            running.set(false)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.apply {
            name = "g3-long-run"
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "G3 long runs",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, G3ChecksActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode G3 long run")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun writeStatus(id: String, message: String, running: Boolean, record: ProbeRecord?) {
        val json = JSONObject()
            .put("id", id)
            .put("message", message)
            .put("running", running)
            .put("updatedAt", System.currentTimeMillis())
        if (record != null) {
            json.put("pass", record.pass)
            json.put("detail", record.detail)
            json.put("durationMs", record.durationMs)
        }
        val file = File(filesDir, "g3/long-run.json")
        file.parentFile?.mkdirs()
        runCatching { file.writeText(json.toString()) }
    }

    companion object {
        const val ACTION_LC3 = "g3.lc3"
        const val ACTION_LC4 = "g3.lc4"
        const val EXTRA_RUNS = "runs"
        const val EXTRA_MINUTES = "minutes"
        private const val CHANNEL_ID = "g3-long-run"
        private const val NOTIFICATION_ID = 4310
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()
    }
}
