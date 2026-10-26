package io.github.spectordev9.opencode.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object NativePtyProbeClient {
    fun runProotShell(
        context: Context,
        proot: String,
        loader: String,
        rootfs: String,
        runtimeRoot: String,
        timeoutMillis: Long,
        testInputBytes: Boolean = false,
    ): String {
        val appContext = context.applicationContext
        val completed = CountDownLatch(1)
        val result = AtomicReference<String?>()
        fun finish(value: String) {
            if (result.compareAndSet(null, value)) completed.countDown()
        }
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == NativePtyProbeService.MESSAGE_RESULT) {
                finish(message.data.getString(NativePtyProbeService.KEY_RESULT).orEmpty())
                true
            } else {
                false
            }
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val request = Message.obtain(null, NativePtyProbeService.MESSAGE_RUN).apply {
                    data = Bundle().apply {
                        putString(NativePtyProbeService.KEY_PROOT, proot)
                        putString(NativePtyProbeService.KEY_LOADER, loader)
                        putString(NativePtyProbeService.KEY_ROOTFS, rootfs)
                        putString(NativePtyProbeService.KEY_RUNTIME_ROOT, runtimeRoot)
                        putLong(NativePtyProbeService.KEY_TIMEOUT_MS, timeoutMillis)
                        putBoolean(NativePtyProbeService.KEY_TEST_INPUT_BYTES, testInputBytes)
                    }
                    replyTo = replyMessenger
                }
                try {
                    Messenger(binder).send(request)
                } catch (failure: RemoteException) {
                    finish("exitCode=-1\n${failure.javaClass.simpleName}: ${failure.message.orEmpty()}\n")
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                finish("exitCode=-1\nnative PTY probe process exited unexpectedly\n")
            }

            override fun onBindingDied(name: ComponentName) {
                finish("exitCode=-1\nnative PTY probe service binding died\n")
            }

            override fun onNullBinding(name: ComponentName) {
                finish("exitCode=-1\nnative PTY probe service returned no binder\n")
            }
        }
        var bound = false
        return try {
            bound = appContext.bindService(
                Intent(appContext, NativePtyProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!bound) return "exitCode=-1\nfailed to bind native PTY probe service\n"
            val waitMillis = timeoutMillis.coerceAtLeast(1L) + SERVICE_TIMEOUT_MARGIN_MS
            if (!completed.await(waitMillis, TimeUnit.MILLISECONDS)) {
                "exitCode=-1\nnative PTY probe service timed out\n"
            } else {
                result.get() ?: "exitCode=-1\nnative PTY probe returned no result\n"
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "exitCode=-1\nnative PTY probe wait interrupted\n"
        } catch (failure: Exception) {
            "exitCode=-1\n${failure.javaClass.simpleName}: ${failure.message.orEmpty()}\n"
        } finally {
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    private const val SERVICE_TIMEOUT_MARGIN_MS = 15_000L
}