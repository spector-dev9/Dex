package io.github.spectordev9.opencode.features

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

enum class TaskNotificationKind {
    COMPLETED,
    PERMISSION,
    FAILED,
}

data class TaskNotification(
    val kind: TaskNotificationKind,
    val title: String,
    val body: String,
    val sessionID: String? = null,
)

/**
 * Local task notifications (F50). Notifications are best-effort and respect
 * the runtime permission on Android 13+; no foreground service or background
 * execution is implied.
 */
class TaskNotifier(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
        as? NotificationManager
    private val appContext = context.applicationContext
    private var nextId = 1_000

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task activity",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Completed tasks, permission requests and failures"
        }
        manager?.createNotificationChannel(channel)
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notify(notification: TaskNotification) {
        if (!hasPermission()) return
        val activeManager = manager ?: return
        ensureChannel()
        val intent = Intent(context, io.github.spectordev9.opencode.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            notification.sessionID?.let { putExtra(EXTRA_SESSION_ID, it) }
        }
        val pending = PendingIntent.getActivity(
            appContext,
            nextId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        val built = builder
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(notification.title.take(80))
            .setContentText(notification.body.take(180))
            .setStyle(Notification.BigTextStyle().bigText(notification.body.take(1_000)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(
                when (notification.kind) {
                    TaskNotificationKind.FAILED -> Notification.CATEGORY_ERROR
                    TaskNotificationKind.PERMISSION -> Notification.CATEGORY_MESSAGE
                    TaskNotificationKind.COMPLETED -> Notification.CATEGORY_STATUS
                },
            )
            .build()
        runCatching { activeManager.notify(nextId++, built) }
    }

    companion object {
        const val CHANNEL_ID = "opencode_tasks"
        const val EXTRA_SESSION_ID = "opencode.session_id"
    }
}
