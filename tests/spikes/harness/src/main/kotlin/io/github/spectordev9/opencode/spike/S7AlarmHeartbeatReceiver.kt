package io.github.spectordev9.opencode.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class S7AlarmHeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SpikeForegroundService.recordAlarmHeartbeat(context)
        if (SpikeForegroundService.isEarlyRunActive(context)) {
            SpikeForegroundService.scheduleAlarmHeartbeat(context)
        }
    }
}