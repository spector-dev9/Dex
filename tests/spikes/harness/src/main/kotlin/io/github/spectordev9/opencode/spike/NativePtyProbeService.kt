package io.github.spectordev9.opencode.spike

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

class NativePtyProbeService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        HarnessCrashHandler.install(this)
        workerThread = HandlerThread("native-pty-probe").apply { start() }
        messenger = Messenger(Handler(workerThread.looper, ::handleMessage))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        workerThread.quitSafely()
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        if (message.what != MESSAGE_RUN) return false
        val data = message.data
        val result = try {
            NativePtyProbe.instance.runProotShell(
                proot = data.getString(KEY_PROOT).orEmpty(),
                loader = data.getString(KEY_LOADER).orEmpty(),
                rootfs = data.getString(KEY_ROOTFS).orEmpty(),
                runtimeRoot = data.getString(KEY_RUNTIME_ROOT).orEmpty(),
                timeoutMillis = data.getLong(KEY_TIMEOUT_MS),
                testInputBytes = data.getBoolean(KEY_TEST_INPUT_BYTES),
            )
        } catch (failure: Throwable) {
            "exitCode=-1\n${failure.javaClass.simpleName}: ${failure.message.orEmpty()}\n"
        }
        val response = Message.obtain(null, MESSAGE_RESULT).apply {
            this.data = Bundle().apply { putString(KEY_RESULT, result) }
        }
        return try {
            message.replyTo?.send(response)
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val MESSAGE_RUN = 1
        const val MESSAGE_RESULT = 2
        const val KEY_PROOT = "proot"
        const val KEY_LOADER = "loader"
        const val KEY_ROOTFS = "rootfs"
        const val KEY_RUNTIME_ROOT = "runtimeRoot"
        const val KEY_TIMEOUT_MS = "timeoutMillis"
        const val KEY_TEST_INPUT_BYTES = "testInputBytes"
        const val KEY_RESULT = "result"
    }
}