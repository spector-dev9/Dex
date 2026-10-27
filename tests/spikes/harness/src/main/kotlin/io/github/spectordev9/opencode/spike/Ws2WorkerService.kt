package io.github.spectordev9.opencode.spike

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService
import java.io.File
import kotlinx.coroutines.runBlocking

class Ws2WorkerService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        if (intent == null) return Binder()
        val root = intent.getStringExtra(EXTRA_ROOT)
        val run = intent.getIntExtra(EXTRA_RUN, -1)
        val size = intent.getIntExtra(EXTRA_SIZE, -1)
        if (root == null || run < 0 || size <= 0) return Binder()
        Thread {
            try {
                val directory = File(root)
                directory.mkdirs()
                File(directory, PID_FILE).writeText(Process.myPid().toString())
                val service = FileWorkspaceService(directory.toPath())
                runBlocking {
                    service.write("target.bin", generatedContent(run, size), Precondition.None)
                }
            } catch (_: Throwable) {
            }
        }.apply {
            name = "ws2-atomic-write"
            isDaemon = true
            start()
        }
        return Binder()
    }

    companion object {
        const val EXTRA_ROOT = "root"
        const val EXTRA_RUN = "run"
        const val EXTRA_SIZE = "size"
        const val PID_FILE = "ws2.pid"

        fun generatedContent(run: Int, size: Int): ByteArray {
            val header = "G3WS2:$run:$size:".toByteArray(Charsets.US_ASCII)
            val data = ByteArray(size)
            header.copyInto(data, 0, 0, minOf(header.size, size))
            data.fill((0x41 + (run % 26)).toByte(), header.size, size)
            return data
        }
    }
}
